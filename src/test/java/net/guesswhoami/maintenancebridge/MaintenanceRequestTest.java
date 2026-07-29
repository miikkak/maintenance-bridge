package net.guesswhoami.maintenancebridge;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

class MaintenanceRequestTest {

    private final Gson gson = new Gson();

    @Test
    void globalRequestParsesAndValidates() {
        final MaintenanceRequest request = gson.fromJson(
                "{\"maintenance\":true,\"reason\":\"Planned restart\",\"minutes\":15,\"server\":null}",
                MaintenanceRequest.class);

        assertDoesNotThrow(request::validate);
        assertTrue(request.maintenance());
        assertEquals(15L, request.minutes());
        assertNull(request.server());
    }

    @Test
    void missingMaintenanceFieldFailsValidation() {
        final MaintenanceRequest request = gson.fromJson("{\"reason\":\"oops\"}", MaintenanceRequest.class);

        assertThrows(IllegalArgumentException.class, request::validate);
    }

    @Test
    void negativeMinutesFailsValidation() {
        final MaintenanceRequest request =
                gson.fromJson("{\"maintenance\":true,\"minutes\":-5}", MaintenanceRequest.class);

        assertThrows(IllegalArgumentException.class, request::validate);
    }

    @Test
    void perServerRequestKeepsServerName() {
        final MaintenanceRequest request =
                gson.fromJson("{\"maintenance\":false,\"server\":\"lobby\"}", MaintenanceRequest.class);

        assertDoesNotThrow(request::validate);
    }

    @Test
    void perServerRequestWithReasonFailsValidation() {
        final MaintenanceRequest request = gson.fromJson(
                "{\"maintenance\":true,\"server\":\"lobby\",\"reason\":\"oops\"}", MaintenanceRequest.class);

        assertThrows(IllegalArgumentException.class, request::validate);
    }

    @Test
    void perServerRequestWithMinutesFailsValidation() {
        final MaintenanceRequest request = gson.fromJson(
                "{\"maintenance\":true,\"server\":\"lobby\",\"minutes\":15}", MaintenanceRequest.class);

        assertThrows(IllegalArgumentException.class, request::validate);
    }

    @Test
    void excessiveMinutesFailsValidation() {
        final MaintenanceRequest request =
                gson.fromJson("{\"maintenance\":true,\"minutes\":999999999}", MaintenanceRequest.class);

        assertThrows(IllegalArgumentException.class, request::validate);
    }
}
