package com.vestri.dynamic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.LoggerFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

class CountryLookupTest {
    @TempDir
    Path directory;

    @Test
    void missingDatabaseIsUnknownWithoutNetwork() {
        try (CountryLookup lookup = new CountryLookup(directory, LoggerFactory.getLogger(CountryLookupTest.class), false)) {
            assertFalse(lookup.available());
            assertNull(lookup.country("1.1.1.1"));
            assertNull(lookup.country("2606:4700:4700::1111"));
            assertFalse(Files.exists(directory.resolve("vestri-country.mmdb")));
        }
    }

    @Test
    void invalidPrivateAndIpv6AddressesAreSafe() throws Exception {
        writeFixture("US");
        try (CountryLookup lookup = new CountryLookup(directory, LoggerFactory.getLogger(CountryLookupTest.class), false)) {
            assertTrue(lookup.available());
            assertEquals("US", lookup.country("1.1.1.1"));
            assertEquals("US", lookup.country("2606:4700:4700::1111"));
            assertEquals("US", lookup.country("::ffff:1.1.1.1"));
            assertNull(lookup.country(null));
            for (String ip : new String[] {"", "localhost", "example.com", "1.1.1.1.example.com", " 1.1.1.1",
                    "1.1.1.1:443", "[::1]", "256.1.1.1", "1.1.1", "16843009", "01.1.1.1", "1..1.1",
                    "::g", "::::", "fe80::1%eth0", "::ffff:999.1.1.1", "::ffff:127.0.0.1", "::",
                    "::1", "fc00::1", "fd00::1", "fe80::1", "ff02::1", "2001:db8::1", "0.0.0.0",
                    "127.0.0.1", "10.0.0.1", "172.16.0.1", "192.168.0.1", "169.254.1.1", "100.64.0.1",
                    "192.0.2.1", "198.18.0.1", "198.51.100.1", "203.0.113.1", "224.0.0.1", "255.255.255.255"}) {
                assertNull(lookup.country(ip), ip);
            }
        }
    }

    @Test
    void currentMonthSkipsDownloadAndMemoryReaderSurvivesFileRemoval() throws Exception {
        Path database = writeFixture("DE");
        Files.setLastModifiedTime(database, FileTime.from(Instant.now()));
        try (CountryLookup lookup = new CountryLookup(directory, LoggerFactory.getLogger(CountryLookupTest.class), false)) {
            assertTrue(lookup.available());
            lookup.refresh();
            assertTrue(lookup.available());
            assertEquals("DE", lookup.country("8.8.8.8"));
            assertEquals(1L, countFiles());
            Files.delete(database);
            assertEquals("DE", lookup.country("1.1.1.1"));
            lookup.close();
            lookup.refresh();
            assertFalse(lookup.available());
            assertNull(lookup.country("1.1.1.1"));
            assertEquals(0L, countFiles());
        }
    }

    @Test
    void onlyExactUppercaseIsoCountriesPassCandidateValidation() throws Exception {
        for (String code : new String[] {"us", "ZZ", "USA", "", " US"}) {
            writeFixture(code);
            try (CountryLookup lookup = new CountryLookup(directory, LoggerFactory.getLogger(CountryLookupTest.class), false)) {
                assertFalse(lookup.available(), code);
                assertNull(lookup.country("1.1.1.1"));
            }
        }
    }

    @Test
    void corruptDatabaseStaysUnavailableAndClosePreventsRefresh() throws Exception {
        Path database = directory.resolve("vestri-country.mmdb");
        Files.writeString(database, "not an MMDB");
        CountryLookup lookup = new CountryLookup(directory, LoggerFactory.getLogger(CountryLookupTest.class), false);
        assertFalse(lookup.available());
        lookup.close();
        lookup.close();
        lookup.refresh();
        assertFalse(lookup.available());
        assertNull(lookup.country("1.1.1.1"));
        assertEquals("not an MMDB", Files.readString(database));
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "VESTRI_GEOIP_TEST", matches = "1")
    void liveDbIpDownloadAndOfflineRestart() {
        try (CountryLookup lookup = new CountryLookup(directory, LoggerFactory.getLogger(CountryLookupTest.class), false)) {
            lookup.refresh();
            assertTrue(lookup.available(), "Live DB-IP download must install a valid database");
            assertTrue(java.util.Arrays.asList(java.util.Locale.getISOCountries()).contains(lookup.country("1.1.1.1")));
            assertNull(lookup.country("10.0.0.1"));
            assertNull(lookup.country("fd00::1"));
            assertNull(lookup.country("localhost"));
        }
        try (CountryLookup lookup = new CountryLookup(directory, LoggerFactory.getLogger(CountryLookupTest.class), false)) {
            assertTrue(lookup.available());
            lookup.refresh();
            assertTrue(lookup.available());
        }
    }

    private long countFiles() throws IOException {
        try (var files = Files.list(directory)) {
            return files.count();
        }
    }

    private Path writeFixture(String code) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(new byte[] {0, 0, 17, 0, 0, 17});
        output.write(new byte[16]);
        encode(output, Map.of("country", Map.of("iso_code", code)));
        output.write(new byte[] {(byte) 0xab, (byte) 0xcd, (byte) 0xef});
        output.write("MaxMind.com".getBytes(StandardCharsets.US_ASCII));
        encode(output, Map.of("node_count", 1, "record_size", 24, "ip_version", 6,
                "database_type", "Vestri-Country-Test", "languages", List.of("en"),
                "binary_format_major_version", 2, "binary_format_minor_version", 0,
                "build_epoch", 1L, "description", Map.of("en", "Synthetic country test")));
        Path database = directory.resolve("vestri-country.mmdb");
        Files.write(database, output.toByteArray());
        return database;
    }

    private static void encode(ByteArrayOutputStream output, Object value) throws IOException {
        if (value instanceof Map<?, ?> map) {
            output.write(0xe0 | map.size());
            for (var entry : map.entrySet()) {
                encode(output, entry.getKey());
                if (entry.getKey().equals("node_count")) {
                    output.write(0xc1);
                    output.write((Integer) entry.getValue());
                } else {
                    encode(output, entry.getValue());
                }
            }
        } else if (value instanceof List<?> list) {
            output.write(list.size());
            output.write(4);
            for (Object element : list) encode(output, element);
        } else if (value instanceof String text) {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            output.write(0x40 | bytes.length);
            output.write(bytes);
        } else if (value instanceof Integer number) {
            output.write(0xa1);
            output.write(number);
        } else if (value instanceof Long number) {
            output.write(1);
            output.write(2);
            output.write(number.intValue());
        } else {
            throw new IllegalArgumentException("Unsupported MMDB test value");
        }
    }
}