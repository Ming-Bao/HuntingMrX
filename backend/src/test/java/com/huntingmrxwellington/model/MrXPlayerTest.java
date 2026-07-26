package com.huntingmrxwellington.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class MrXPlayerTest {

    private MrXPlayer player(int detectiveCount) {
        return new MrXPlayer("id-mrx", "Mr X", detectiveCount);
    }

    // --- Role ---

    @Test
    void role_isMrX() {
        assertThat(player(2).getRole()).isEqualTo(Role.MR_X);
    }

    // --- Unlimited transport tickets ---

    @Test
    void transportTickets_areUnlimited() {
        MrXPlayer p = player(3);
        assertThat(p.getTicket(TicketType.ESCOOTER)).isEqualTo(-1);
        assertThat(p.getTicket(TicketType.BUS)).isEqualTo(-1);
        assertThat(p.getTicket(TicketType.TRAIN)).isEqualTo(-1);
        assertThat(p.getTicket(TicketType.FERRY)).isEqualTo(-1);
    }

    @Test
    void useTicket_unlimitedTransport_doesNotDecrement() {
        MrXPlayer p = player(2);
        p.useTicket(TicketType.ESCOOTER);
        p.useTicket(TicketType.BUS);
        p.useTicket(TicketType.TRAIN);
        p.useTicket(TicketType.FERRY);
        assertThat(p.getTicket(TicketType.ESCOOTER)).isEqualTo(-1);
        assertThat(p.getTicket(TicketType.BUS)).isEqualTo(-1);
        assertThat(p.getTicket(TicketType.TRAIN)).isEqualTo(-1);
        assertThat(p.getTicket(TicketType.FERRY)).isEqualTo(-1);
    }

    // --- DOUBLE tickets ---

    @Test
    void doubleTickets_startAtTwo() {
        assertThat(player(1).getTicket(TicketType.DOUBLE)).isEqualTo(2);
    }

    @Test
    void useTicket_double_decrements() {
        MrXPlayer p = player(1);
        p.useTicket(TicketType.DOUBLE);
        assertThat(p.getTicket(TicketType.DOUBLE)).isEqualTo(1);
        p.useTicket(TicketType.DOUBLE);
        assertThat(p.getTicket(TicketType.DOUBLE)).isEqualTo(0);
    }

    @Test
    void useTicket_doubleExhausted_throws() {
        MrXPlayer p = player(1);
        p.useTicket(TicketType.DOUBLE);
        p.useTicket(TicketType.DOUBLE);
        assertThatThrownBy(() -> p.useTicket(TicketType.DOUBLE))
                .isInstanceOf(IllegalStateException.class);
    }

    // --- BLACK tickets ---

    @Test
    void blackTickets_equalDetectiveCount() {
        assertThat(player(1).getTicket(TicketType.BLACK)).isEqualTo(1);
        assertThat(player(3).getTicket(TicketType.BLACK)).isEqualTo(3);
        assertThat(player(5).getTicket(TicketType.BLACK)).isEqualTo(5);
    }

    @Test
    void useTicket_black_decrements() {
        MrXPlayer p = player(2);
        p.useTicket(TicketType.BLACK);
        assertThat(p.getTicket(TicketType.BLACK)).isEqualTo(1);
    }

    @Test
    void useTicket_blackExhausted_throws() {
        MrXPlayer p = player(1);
        p.useTicket(TicketType.BLACK);
        assertThatThrownBy(() -> p.useTicket(TicketType.BLACK))
                .isInstanceOf(IllegalStateException.class);
    }

    // --- Ticket map contents ---

    @Test
    void tickets_mapContainsAllSixTypes() {
        MrXPlayer p = player(2);
        assertThat(p.getTickets()).containsKeys(
                TicketType.ESCOOTER, TicketType.BUS, TicketType.TRAIN,
                TicketType.FERRY, TicketType.DOUBLE, TicketType.BLACK);
    }

    @Test
    void tickets_mapIsUnmodifiable() {
        MrXPlayer p = player(2);
        assertThatThrownBy(() -> p.getTickets().put(TicketType.DOUBLE, 99))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
