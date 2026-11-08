package com.vestri.dynamic;

import com.maxmind.db.Reader;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import java.util.zip.GZIPInputStream;

public final class CountryLookup implements AutoCloseable {
    private static final long MAX_DATABASE_BYTES = 64L * 1024 * 1024;
    private static final Set<String> ISO_COUNTRIES = Set.copyOf(Arrays.asList(Locale.getISOCountries()));
    private final Path database;
    private final Logger logger;
    private final Object readerLock = new Object();
    private final Object updateLock = new Object();
    private final ScheduledExecutorService worker;
    private Reader reader;
    private volatile boolean closed;
    private HttpURLConnection activeConnection;

    public CountryLookup(Path directory, Logger logger) {
        this(directory, logger, true);
    }

    CountryLookup(Path directory, Logger logger, boolean automaticRefresh) {
        this.database = directory.resolve("vestri-country.mmdb");
        this.logger = logger;
        this.worker = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "vestri-country-refresh");
            thread.setDaemon(true);
            return thread;
        });
        if (Files.isRegularFile(database)) {
            try {
                reader = openValidated(database);
            } catch (IOException | RuntimeException exception) {
                logger.warn("Cannot load local country database: {}", exception.getMessage());
            }
        }
        if (automaticRefresh) {
            worker.scheduleWithFixedDelay(this::refresh, 0, 1, TimeUnit.DAYS);
        }
    }

    public String country(String ip) {
        InetAddress address = publicAddress(ip);
        if (address == null) return null;
        synchronized (readerLock) {
            if (closed || reader == null) return null;
            try {
                return lookup(reader, address);
            } catch (IOException | RuntimeException exception) {
                return null;
            }
        }
    }

    public boolean available() {
        synchronized (readerLock) {
            return !closed && reader != null;
        }
    }

    public void refresh() {
        synchronized (updateLock) {
            if (closed) return;
            Path candidatePath = null;
            Reader candidate = null;
            HttpURLConnection connection = null;
            try {
                YearMonth month = YearMonth.now(ZoneOffset.UTC);
                synchronized (readerLock) {
                    if (closed) return;
                    if (reader != null && Files.isRegularFile(database)
                            && month.equals(YearMonth.from(Files.getLastModifiedTime(database)
                                    .toInstant().atZone(ZoneOffset.UTC)))) return;
                }
                Files.createDirectories(database.getParent());
                candidatePath = Files.createTempFile(database.getParent(), "vestri-country-", ".mmdb.tmp");
                connection = (HttpURLConnection) URI.create("https://download.db-ip.com/free/dbip-country-lite-"
                        + month + ".mmdb.gz").toURL().openConnection();
                connection.setConnectTimeout(10_000);
                connection.setReadTimeout(60_000);
                connection.setInstanceFollowRedirects(false);
                synchronized (readerLock) {
                    if (closed) return;
                    activeConnection = connection;
                }
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
                if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                    throw new IOException("DB-IP download returned HTTP " + connection.getResponseCode());
                }
                try (InputStream body = new DeadlineInputStream(connection.getInputStream(), connection, deadline);
                        GZIPInputStream gzip = new GZIPInputStream(body);
                        OutputStream output = Files.newOutputStream(candidatePath)) {
                    byte[] buffer = new byte[8192];
                    long total = 0;
                    int count;
                    while ((count = gzip.read(buffer)) != -1) {
                        total += count;
                        if (total > MAX_DATABASE_BYTES) throw new IOException("Country database exceeds 64 MiB");
                        output.write(buffer, 0, count);
                    }
                }
                candidate = openValidated(candidatePath);
                synchronized (readerLock) {
                    if (closed) return;
                    Files.setLastModifiedTime(candidatePath, FileTime.from(Instant.now()));
                    Files.move(candidatePath, database, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                    Reader previous = reader;
                    reader = candidate;
                    candidate = null;
                    closeReader(previous);
                }
            } catch (IOException | RuntimeException exception) {
                if (!closed) logger.warn("Country database update failed; retaining local data and retrying daily: {}",
                    exception.getMessage());
            } finally {
                synchronized (readerLock) {
                    if (activeConnection == connection) activeConnection = null;
                }
                if (connection != null) connection.disconnect();
                closeReader(candidate);
                if (candidatePath != null) {
                    try {
                        Files.deleteIfExists(candidatePath);
                    } catch (IOException exception) {
                        logger.warn("Cannot remove temporary country database: {}", exception.getMessage());
                    }
                }
            }
        }
    }

    @Override
    public void close() {
        synchronized (readerLock) {
            if (closed) return;
            closed = true;
            worker.shutdownNow();
            if (activeConnection != null) activeConnection.disconnect();
            closeReader(reader);
            reader = null;
        }
    }

    private void closeReader(Reader value) {
        if (value == null) return;
        try {
            value.close();
        } catch (IOException exception) {
            logger.warn("Cannot close country database: {}", exception.getMessage());
        }
    }

    private static Reader openValidated(Path path) throws IOException {
        if (Files.size(path) > MAX_DATABASE_BYTES) throw new IOException("Country database exceeds 64 MiB");
        Reader candidate = new Reader(path.toFile(), Reader.FileMode.MEMORY);
        try {
            if (lookup(candidate, InetAddress.getByAddress(new byte[] {1, 1, 1, 1})) == null) {
                throw new IOException("Country database has no valid country for validation address");
            }
            return candidate;
        } catch (IOException | RuntimeException exception) {
            candidate.close();
            throw exception;
        }
    }

    private static String lookup(Reader source, InetAddress address) throws IOException {
        Map<?, ?> record = source.get(address, Map.class);
        if (record == null || !(record.get("country") instanceof Map<?, ?> country)) return null;
        Object code = country.get("iso_code");
        return code instanceof String value && ISO_COUNTRIES.contains(value) ? value : null;
    }

    private static InetAddress publicAddress(String ip) {
        if (ip == null || ip.isEmpty() || ip.length() > 45) return null;
        try {
            InetAddress address;
            if (ip.indexOf(':') >= 0) {
                if (!ip.matches("[0-9A-Fa-f:.]+")) return null;
                if (ip.indexOf('.') >= 0 && ipv4Bytes(ip.substring(ip.lastIndexOf(':') + 1)) == null) return null;
                address = InetAddress.getByName(ip);
            } else {
                byte[] bytes = ipv4Bytes(ip);
                if (bytes == null) return null;
                address = InetAddress.getByAddress(bytes);
            }
            if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress() || address.isMulticastAddress()) return null;
            byte[] bytes = address.getAddress();
            if (bytes.length == 4) {
                int first = Byte.toUnsignedInt(bytes[0]);
                int second = Byte.toUnsignedInt(bytes[1]);
                int third = Byte.toUnsignedInt(bytes[2]);
                if (first == 0 || first >= 224 || (first == 100 && second >= 64 && second <= 127)
                        || (first == 192 && second == 0 && (third == 0 || third == 2))
                        || (first == 198 && (second == 18 || second == 19 || (second == 51 && third == 100)))
                        || (first == 203 && second == 0 && third == 113)) return null;
            } else {
                if ((bytes[0] & 0xe0) != 0x20
                        || (bytes[0] == 0x20 && bytes[1] == 0x01 && bytes[2] == 0x0d && bytes[3] == (byte) 0xb8)) {
                    return null;
                }
            }
            return address;
        } catch (IOException | IllegalArgumentException exception) {
            return null;
        }
    }

    private static byte[] ipv4Bytes(String ip) {
        String[] parts = ip.split("\\.", -1);
        if (parts.length != 4) return null;
        byte[] bytes = new byte[4];
        for (int index = 0; index < parts.length; index++) {
            String part = parts[index];
            if (!part.matches("[0-9]{1,3}") || (part.length() > 1 && part.charAt(0) == '0')) return null;
            int value = Integer.parseInt(part);
            if (value > 255) return null;
            bytes[index] = (byte) value;
        }
        return bytes;
    }

    private final class DeadlineInputStream extends FilterInputStream {
        private final HttpURLConnection connection;
        private final long deadline;

        private DeadlineInputStream(InputStream input, HttpURLConnection connection, long deadline) {
            super(input);
            this.connection = connection;
            this.deadline = deadline;
        }

        private void checkDeadline() throws IOException {
            long remaining = deadline - System.nanoTime();
            if (closed || Thread.currentThread().isInterrupted()) throw new IOException("Country update cancelled");
            if (remaining <= 0) throw new SocketTimeoutException("Country download exceeded 60 seconds");
            connection.setReadTimeout((int) Math.max(1, Math.min(60_000, TimeUnit.NANOSECONDS.toMillis(remaining))));
        }

        @Override
        public int read() throws IOException {
            checkDeadline();
            int value = in.read();
            checkDeadline();
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            checkDeadline();
            int count = in.read(buffer, offset, length);
            checkDeadline();
            return count;
        }
    }
}