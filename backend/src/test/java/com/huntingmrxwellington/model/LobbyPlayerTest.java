package com.huntingmrxwellington.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class LobbyPlayerTest {

    private LobbyPlayer player() {
        return new LobbyPlayer("id-lobby", "Charlie");
    }

    @Test
    void role_isNull() {
        assertThat(player().getRole()).isNull();
    }

    @Test
    void tickets_isNull() {
        assertThat(player().getTickets()).isNull();
    }

    @Test
    void getTicket_isNull() {
        assertThat(player().getTicket(TicketType.BUS)).isNull();
    }

    @Test
    void useTicket_throwsUnsupported() {
        assertThatThrownBy(() -> player().useTicket(TicketType.BUS))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void nodeId_startsNull() {
        assertThat(player().getNodeId()).isNull();
    }

    @Test
    void getId_andGetName_returnConstructorValues() {
        LobbyPlayer p = player();
        assertThat(p.getId()).isEqualTo("id-lobby");
        assertThat(p.getName()).isEqualTo("Charlie");
    }
}
