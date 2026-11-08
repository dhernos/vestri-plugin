package com.vestri.dynamic;

import static org.junit.jupiter.api.Assertions.*;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

class PartyCommandTest {
    private final Parties parties = new Parties();
    private final Map<UUID, Session> online = new LinkedHashMap<>();
    private final ProxyServer proxy = stub(ProxyServer.class, (instance, method, arguments) -> switch (method.getName()) {
        case "getPlayer" -> online.values().stream()
                .filter(session -> session.id.equals(arguments[0]) || session.name.equalsIgnoreCase(arguments[0].toString()))
                .map(session -> session.player).findFirst();
        case "getAllPlayers" -> online.values().stream().map(session -> session.player).toList();
        default -> throw new AssertionError("Unexpected proxy call: " + method.getName());
    });
    private final PartyCommand command = new PartyCommand(proxy, parties);
    private final Session leader = session("Leader");
    private final Session guest = session("Guest");
    private final RegisteredServer target = server("Survival");

    @Test
    void inviteAcceptFollowsLeadersCurrentServerThroughConnectionRequest() {
        leader.current = target;
        execute(leader, "invite", guest.name);
        assertEquals(List.of("Leader invited you. /party accept Leader"), guest.messages);
        execute(guest, "accept", leader.name);
        assertEquals(List.of(leader.id, guest.id), parties.members(guest.id));
        assertEquals(List.of(target), guest.requests);
        assertFalse(guest.attempts.get(0).isDone());
        guest.attempts.get(0).complete(result(true));
        assertEquals("Party updated.", guest.lastMessage());
        assertTrue(leader.requests.isEmpty());
    }

    @Test
    void acceptWithoutLeaderServerChangesMembershipWithoutConnecting() {
        execute(leader, "invite", guest.name);
        execute(guest, "accept", leader.name);
        assertEquals(leader.id, parties.leader(guest.id));
        assertTrue(guest.requests.isEmpty());
        assertEquals("Party updated.", guest.lastMessage());
    }

    @Test
    void acceptAlreadyOnLeaderServerDoesNotReconnect() {
        leader.current = target;
        guest.current = target;
        execute(leader, "invite", guest.name);
        execute(guest, "accept", leader.name);
        assertEquals(leader.id, parties.leader(guest.id));
        assertTrue(guest.requests.isEmpty());
    }

    @Test
    void invalidAcceptDoesNotFollowOrChangeMembership() {
        leader.current = target;
        execute(guest, "accept", leader.name);
        assertEquals("No valid invitation from that player.", guest.lastMessage());
        assertEquals(List.of(guest.id), parties.members(guest.id));
        assertTrue(guest.requests.isEmpty());
    }

    @Test
    void rejectedAcceptFollowKeepsPartyButDoesNotClaimConnectionSuccess() {
        leader.current = target;
        execute(leader, "invite", guest.name);
        execute(guest, "accept", leader.name);
        guest.attempts.get(0).complete(result(false));
        assertEquals("Could not follow the party leader to Survival.", guest.lastMessage());
        assertEquals(leader.id, parties.leader(guest.id));
        assertEquals(1, guest.requests.size());
        assertFalse(guest.messages.stream().anyMatch(message -> message.startsWith("Connected")));
    }

    @Test
    void exceptionalFollowReportsFailureWithoutRetry() {
        join(guest);
        command.connected(leader.player, target);
        guest.attempts.get(0).completeExceptionally(new java.net.ConnectException("refused"));
        assertEquals(List.of("Could not follow the party leader to Survival."), guest.messages);
        assertEquals(1, guest.requests.size());
        assertEquals(leader.id, parties.leader(guest.id));
    }

    @Test
    void successfulFollowDoesNotFanOutFromFollowerOrLoopOnLeaderReconnect() {
        join(guest);
        command.connected(leader.player, target);
        assertEquals(List.of(target), guest.requests);
        guest.current = target;
        guest.attempts.get(0).complete(result(true));
        command.connected(guest.player, target);
        command.connected(leader.player, target);
        assertEquals(1, guest.requests.size());
        assertTrue(leader.requests.isEmpty());
        assertTrue(guest.messages.isEmpty());
    }

    @Test
    void followsOnlyOnlineMembersNotUnacceptedInvitesOrUnrelatedPlayers() {
        Session offline = session("Offline");
        Session pending = session("Pending");
        Session stranger = session("Stranger");
        join(guest);
        join(offline);
        parties.invite(leader.id, pending.id, 0);
        online.remove(offline.id);
        command.connected(leader.player, target);
        assertEquals(List.of(target), guest.requests);
        assertTrue(offline.requests.isEmpty());
        assertTrue(pending.requests.isEmpty());
        assertTrue(stranger.requests.isEmpty());
    }

    @Test
    void rejectedFollowReportsFailureWithoutRetryOrDroppingMembership() {
        join(guest);
        command.connected(leader.player, target);
        guest.attempts.get(0).complete(result(false));
        assertEquals("Could not follow the party leader to Survival.", guest.lastMessage());
        assertEquals(leader.id, parties.leader(guest.id));
        assertEquals(1, guest.requests.size());
        assertTrue(leader.requests.isEmpty());
    }

    @Test
    void followUsesProxyAdmissionRatherThanCommandPermissionChecks() {
        join(guest);
        command.connected(leader.player, target);
        assertEquals(List.of(target), guest.requests);
        assertEquals(0, guest.permissionChecks);
        guest.attempts.get(0).complete(result(false));
        assertEquals(1, guest.messages.size());
        assertTrue(guest.lastMessage().startsWith("Could not follow"));
    }

    @Test
    void leadershipTransferChangesWhoCanTriggerFollowing() {
        join(guest);
        Session third = session("Third");
        join(third);
        execute(leader, "leader", guest.name);
        assertEquals(guest.id, parties.leader(third.id));
        assertEquals(List.of(leader.id, guest.id, third.id), parties.members(leader.id));
        command.connected(leader.player, target);
        assertTrue(third.requests.isEmpty());
        command.connected(guest.player, target);
        assertEquals(List.of(target), leader.requests);
        assertEquals(List.of(target), third.requests);
        assertTrue(guest.requests.isEmpty());
    }

    @Test
    void sameLeaderTransferKeepsGroupAndFollowingIntact() {
        join(guest);
        execute(leader, "leader", leader.name);
        assertEquals(List.of(leader.id, guest.id), parties.members(guest.id));
        assertEquals(leader.id, parties.leader(guest.id));
        command.connected(leader.player, target);
        assertEquals(List.of(target), guest.requests);
    }

    @Test
    void disconnectCleanupPromotesFirstRemainingMemberAndStopsOldLeaderFollowing() {
        join(guest);
        Session third = session("Third");
        join(third);
        online.remove(leader.id);
        parties.leave(leader.id);
        assertEquals(guest.id, parties.leader(third.id));
        assertEquals(List.of(guest.id, third.id), parties.members(third.id));
        command.connected(leader.player, target);
        assertTrue(third.requests.isEmpty());
        command.connected(guest.player, target);
        assertEquals(List.of(target), third.requests);
        online.remove(third.id);
        parties.leave(third.id);
        assertEquals(List.of(guest.id), parties.members(guest.id));
        parties.leave(guest.id);
        assertEquals(List.of(guest.id), parties.members(guest.id));
    }

    @Test
    void selfAndExistingMemberInvitesAreRejectedWithoutNotification() {
        execute(leader, "invite", leader.name);
        assertEquals("That player is already in a party or is yourself.", leader.lastMessage());
        join(guest);
        execute(leader, "invite", guest.name);
        assertEquals("That player is already in a party or is yourself.", leader.lastMessage());
        assertTrue(guest.messages.isEmpty());
        assertEquals(2, parties.members(leader.id).size());
    }

    @Test
    void nonLeaderCannotInviteKickTransferOrDisband() {
        join(guest);
        Session other = session("Other");
        for (String action : List.of("invite", "kick", "leader", "disband")) {
            if (action.equals("disband")) {
                execute(guest, action);
            } else {
                execute(guest, action, other.name);
            }
            assertEquals("Only the party leader can do that.", guest.lastMessage());
            assertEquals(List.of(leader.id, guest.id), parties.members(guest.id));
        }
        assertTrue(other.messages.isEmpty());
    }

    @Test
    void expiredWrongLeaderAndConsumedInvitesCannotBeAccepted() {
        parties.invite(leader.id, guest.id, System.currentTimeMillis() - 120001);
        execute(guest, "accept", leader.name);
        assertEquals("No valid invitation from that player.", guest.lastMessage());
        execute(leader, "invite", guest.name);
        Session other = session("Other");
        execute(guest, "accept", other.name);
        assertEquals("No valid invitation from that player.", guest.lastMessage());
        execute(guest, "accept", leader.name);
        assertEquals(leader.id, parties.leader(guest.id));
        execute(guest, "accept", leader.name);
        assertEquals("No valid invitation from that player.", guest.lastMessage());
    }

    @Test
    void disbandAndRecreationDoNotReviveStaleInvites() {
        execute(leader, "invite", guest.name);
        execute(leader, "disband");
        Session other = session("Other");
        execute(leader, "invite", other.name);
        execute(guest, "accept", leader.name);
        assertEquals("No valid invitation from that player.", guest.lastMessage());
        assertEquals(List.of(guest.id), parties.members(guest.id));
    }

    @Test
    void leadershipTransferInvalidatesOldInvitesIncludingSameLeaderTransfer() {
        join(guest);
        Session pending = session("Pending");
        execute(leader, "invite", pending.name);
        execute(leader, "leader", leader.name);
        execute(pending, "accept", leader.name);
        assertEquals("No valid invitation from that player.", pending.lastMessage());
        execute(leader, "invite", pending.name);
        execute(leader, "leader", guest.name);
        execute(pending, "accept", leader.name);
        assertEquals("No valid invitation from that player.", pending.lastMessage());
        execute(pending, "accept", guest.name);
        assertEquals("No valid invitation from that player.", pending.lastMessage());
    }

    @Test
    void partyCapacityIsCheckedAgainWhenAcceptingOutstandingInvite() {
        for (int index = 0; index < 98; index++) {
            UUID member = UUID.randomUUID();
            parties.invite(leader.id, member, 0);
            parties.accept(member, leader.id, 1);
        }
        Session last = session("Last");
        execute(leader, "invite", guest.name);
        execute(leader, "invite", last.name);
        execute(last, "accept", leader.name);
        execute(guest, "accept", leader.name);
        assertEquals("Already in a party, or the party is full.", guest.lastMessage());
        assertEquals(100, parties.members(leader.id).size());
        assertTrue(guest.requests.isEmpty());
        execute(leader, "invite", guest.name);
        assertEquals("The party is full.", leader.lastMessage());
    }

    @Test
    void kickAndLeaveRemoveMembersAndRejectSelfOrOutsiderKick() {
        join(guest);
        execute(leader, "kick", leader.name);
        assertEquals("That player is not another party member.", leader.lastMessage());
        execute(leader, "kick", guest.name);
        assertEquals(List.of(leader.id), parties.members(leader.id));
        execute(leader, "kick", guest.name);
        assertEquals("That player is not another party member.", leader.lastMessage());
        execute(guest, "leave");
        assertEquals("You left the party.", guest.lastMessage());
        execute(leader, "disband");
        assertEquals("Party disbanded.", leader.lastMessage());
    }

    @Test
    void suggestionsFilterActionAndPlayerPrefixes() {
        assertEquals(List.of("invite", "accept", "leave", "kick", "list", "disband", "leader"),
                command.suggest(invocation(leader)));
        assertEquals(List.of("leave", "list", "leader"), command.suggest(invocation(leader, "l")));
        assertTrue(command.suggest(invocation(leader, "unknown")).isEmpty());
        assertEquals(List.of("Guest"), command.suggest(invocation(leader, "invite", "gU")));
        assertTrue(command.suggest(invocation(leader, "invite", "missing")).isEmpty());
    }

    @Test
    void defaultListUsesNamesAndOfflineFallbackAndMalformedCommandsDoNotMutate() {
        join(guest);
        execute(leader);
        assertEquals("Party leader: Leader; members: Leader, Guest", leader.lastMessage());
        online.remove(guest.id);
        execute(leader, "list");
        assertEquals("Party leader: Leader; members: Leader, offline member", leader.lastMessage());
        execute(leader, "invite");
        assertEquals("Usage: /party invite <player>", leader.lastMessage());
        execute(leader, "invite", "Missing");
        assertEquals("That player is not online.", leader.lastMessage());
        execute(leader, "disband", "extra");
        assertTrue(leader.lastMessage().startsWith("Usage: /party"));
        assertEquals(2, parties.members(leader.id).size());
    }

    @Test
    void consoleExecutionIsRejectedWithoutMutatingParties() {
        List<String> messages = new ArrayList<>();
        CommandSource console = stub(CommandSource.class, (instance, method, arguments) -> {
            if (method.getName().equals("sendMessage")) {
                messages.add(PlainTextComponentSerializer.plainText().serialize((Component) arguments[0]));
                return null;
            }
            throw new AssertionError("Unexpected console call: " + method.getName());
        });
        command.execute(invocation(console, "invite", guest.name));
        assertEquals(List.of("This command is player-only."), messages);
        assertEquals(List.of(leader.id), parties.members(leader.id));
        assertTrue(guest.messages.isEmpty());
    }

    private void join(Session member) {
        parties.invite(leader.id, member.id, 0);
        parties.accept(member.id, leader.id, 1);
    }

    private void execute(Session actor, String... arguments) {
        command.execute(invocation(actor, arguments));
    }

    private SimpleCommand.Invocation invocation(Session actor, String... arguments) {
        return invocation(actor.player, arguments);
    }

    private SimpleCommand.Invocation invocation(CommandSource source, String... arguments) {
        return stub(SimpleCommand.Invocation.class, (instance, method, values) -> switch (method.getName()) {
            case "source" -> source;
            case "arguments" -> arguments;
            case "alias" -> "party";
            default -> throw new AssertionError("Unexpected invocation call: " + method.getName());
        });
    }

    private Session session(String name) {
        Session session = new Session(name);
        online.put(session.id, session);
        return session;
    }

    private static RegisteredServer server(String name) {
        ServerInfo info = new ServerInfo(name, InetSocketAddress.createUnresolved("localhost", 25565));
        return stub(RegisteredServer.class, (instance, method, arguments) -> switch (method.getName()) {
            case "getServerInfo" -> info;
            default -> throw new AssertionError("Unexpected server call: " + method.getName());
        });
    }

    private static ConnectionRequestBuilder.Result result(boolean successful) {
        return stub(ConnectionRequestBuilder.Result.class, (instance, method, arguments) -> switch (method.getName()) {
            case "isSuccessful" -> successful;
            case "getStatus" -> successful ? ConnectionRequestBuilder.Status.SUCCESS : ConnectionRequestBuilder.Status.CONNECTION_CANCELLED;
            case "getReasonComponent" -> Optional.empty();
            default -> throw new AssertionError("Unexpected result call: " + method.getName());
        });
    }

    private static <Type> Type stub(Class<Type> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (instance, method, arguments) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "equals" -> instance == arguments[0];
                    case "hashCode" -> System.identityHashCode(instance);
                    case "toString" -> type.getSimpleName();
                    default -> throw new AssertionError(method.getName());
                };
            }
            return handler.invoke(instance, method, arguments);
        }));
    }

    private static final class Session {
        final UUID id = UUID.randomUUID();
        final String name;
        final List<String> messages = new ArrayList<>();
        final List<RegisteredServer> requests = new ArrayList<>();
        final List<CompletableFuture<ConnectionRequestBuilder.Result>> attempts = new ArrayList<>();
        final Player player;
        RegisteredServer current;
        int permissionChecks;

        Session(String name) {
            this.name = name;
            player = stub(Player.class, (instance, method, arguments) -> switch (method.getName()) {
                case "getUniqueId" -> id;
                case "getUsername" -> name;
                case "getCurrentServer" -> current == null ? Optional.empty() : Optional.of(
                        stub(ServerConnection.class, (connection, operation, values) -> {
                            if (operation.getName().equals("getServer")) {
                                return current;
                            }
                            throw new AssertionError("Unexpected server connection call: " + operation.getName());
                        }));
                case "hasPermission" -> {
                    permissionChecks++;
                    yield false;
                }
                case "sendMessage" -> {
                    messages.add(PlainTextComponentSerializer.plainText().serialize((Component) arguments[0]));
                    yield null;
                }
                case "createConnectionRequest" -> {
                    requests.add((RegisteredServer) arguments[0]);
                    var attempt = new CompletableFuture<ConnectionRequestBuilder.Result>();
                    attempts.add(attempt);
                    yield stub(ConnectionRequestBuilder.class, (builder, operation, values) -> {
                        if (operation.getName().equals("connect")) {
                            return attempt;
                        }
                        throw new AssertionError("Unexpected connection request call: " + operation.getName());
                    });
                }
                default -> throw new AssertionError("Unexpected player call: " + method.getName());
            });
        }

        String lastMessage() {
            return messages.get(messages.size() - 1);
        }
    }
}