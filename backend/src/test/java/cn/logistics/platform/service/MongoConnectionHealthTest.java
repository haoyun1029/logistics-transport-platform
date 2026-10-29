package cn.logistics.platform.service;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MongoConnectionHealthTest {
    private final MongoTelemetryService service = new MongoTelemetryService(
            "mongodb://probe_user:probe_secret@127.0.0.1:27018/?authSource=admin", "logistics_system", "DEMO");

    @Test void reportsConfiguredTunnelOnlyAfterSuccessfulMongoCommand() {
        MongoClient client = mock(MongoClient.class);
        MongoDatabase database = mock(MongoDatabase.class);
        when(client.getDatabase("logistics_system")).thenReturn(database);
        when(database.runCommand(new Document("ping", 1))).thenReturn(new Document("ok", 1.0));
        var result = service.connectionHealth(() -> client);
        assertEquals("CONNECTED", result.status());
        assertEquals("127.0.0.1:27018", result.endpoint());
        assertFalse(result.toString().contains("probe_secret"));
        verify(client).close();
    }

    @Test void failedTunnelDoesNotClaimVmIsAvailableOrDiscloseDriverError() {
        var result = service.connectionHealth(() -> {
            throw new IllegalStateException("failure for mongodb://probe_user:probe_secret@127.0.0.1");
        });
        assertEquals("UNAVAILABLE", result.status());
        assertFalse(result.toString().contains("probe_secret"));
        assertFalse(result.toString().contains("probe_user"));
    }

    @Test void rejectsUnsuccessfulPingResponseAndClosesConnection() {
        MongoClient client = mock(MongoClient.class);
        MongoDatabase database = mock(MongoDatabase.class);
        when(client.getDatabase("logistics_system")).thenReturn(database);
        when(database.runCommand(new Document("ping", 1))).thenReturn(new Document("ok", 0.0));
        assertEquals("UNAVAILABLE", service.connectionHealth(() -> client).status());
        verify(client).close();
    }
}
