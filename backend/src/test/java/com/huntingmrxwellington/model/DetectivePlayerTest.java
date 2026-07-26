package com.huntingmrxwellington.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class DetectivePlayerTest {

    private DetectivePlayer player() {
        return new DetectivePlayer("id-d1", "Alice", 10, 8, 4, 2);
    }

    // --- Role ---

    @Test
    void role_isDetective() {
        assertThat(player().getRole()).isEqualTo(Role.DETECTIVE);
    }

    // --- Initial ticket counts ---

    @Test
    void tickets_initialCounts_matchConstructor() {
        DetectivePlayer p = player();
        assertThat(p.getTicket(TicketType.ESCOOTER)).isEqualTo(10);
        assertThat(p.getTicket(TicketType.BUS)).isEqualTo(8);
        assertThat(p.getTicket(TicketType.TRAIN)).isEqualTo(4);
        assertThat(p.getTicket(TicketType.FERRY)).isEqualTo(2);
    }

    @Test
    void tickets_noBlackOrDoubleKeys() {
        DetectivePlayer p = player();
        assertThat(p.getTickets()).doesNotContainKeys(TicketType.BLACK, TicketType.DOUBLE);
        assertThat(p.getTicket(TicketType.BLACK)).isNull();
        assertThat(p.getTicket(TicketType.DOUBLE)).isNull();
    }

    // --- useTicket ---

    @Test
    void useTicket_decrementsCorrectly() {
        DetectivePlayer p = player();
        p.useTicket(TicketType.ESCOOTER);
        assertThat(p.getTicket(TicketType.ESCOOTER)).isEqualTo(9);
        p.useTicket(TicketType.BUS);
        assertThat(p.getTicket(TicketType.BUS)).isEqualTo(7);
    }

    @Test
    void useTicket_exhausted_throws() {
        DetectivePlayer p = new DetectivePlayer("id", "Bob", 1, 1, 1, 1);
        p.useTicket(TicketType.FERRY);
        assertThatThrownBy(() -> p.useTicket(TicketType.FERRY))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void useTicket_unknownType_throws() {
        DetectivePlayer p = player();
        assertThatThrownBy(() -> p.useTicket(TicketType.BLACK))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- Ticket map contents ---

    @Test
    void tickets_mapContainsFourTransportTypes() {
        assertThat(player().getTickets()).containsOnlyKeys(
                TicketType.ESCOOTER, TicketType.BUS, TicketType.TRAIN, TicketType.FERRY);
    }

    @Test
    void tickets_mapIsUnmodifiable() {
        assertThatThrownBy(() -> player().getTickets().put(TicketType.ESCOOTER, 99))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // --- Node ---

    @Test
    void nodeId_startsNull() {
        assertThat(player().getNodeId()).isNull();
    }

    @Test
    void nodeId_canBeSet() {
        DetectivePlayer p = player();
        p.setNodeId(42);
        assertThat(p.getNodeId()).isEqualTo(42);
    }
}
