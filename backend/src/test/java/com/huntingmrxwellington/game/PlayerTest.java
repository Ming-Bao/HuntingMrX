package com.huntingmrxwellington.game;

import com.huntingmrxwellington.game.enums.Role;
import com.huntingmrxwellington.game.enums.TicketType;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.huntingmrxwellington.game.enums.TicketType.*;
import static org.assertj.core.api.Assertions.*;

class PlayerTest {

    static Player detective(Map<TicketType, Integer> tickets) {
        Player p = new Player("Alice");
        p.assign(Role.DETECTIVE, 1, tickets);
        return p;
    }

    @Test
    void spendingAFiniteTicketUsesOneUp() {
        Player p = detective(Map.of(BUS, 2));
        p.spend(BUS);
        assertThat(p.tickets()).containsEntry(BUS, 1);
    }

    @Test
    void unlimitedTicketsNeverRunOut() {
        Player p = detective(Map.of(BUS, Player.UNLIMITED));
        for (int i = 0; i < 100; i++) p.spend(BUS);
        assertThat(p.tickets()).containsEntry(BUS, Player.UNLIMITED);
        assertThat(p.has(BUS)).isTrue();
    }

    @Test
    void spendingAnEmptyOrMissingTicketFails() {
        Player p = detective(Map.of(BUS, 1));
        p.spend(BUS);
        assertThat(p.has(BUS)).isFalse();
        assertThatThrownBy(() -> p.spend(BUS)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> p.spend(BLACK)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ticketsReturnsACopy() {
        Player p = detective(Map.of(BUS, 1));
        p.tickets().put(BUS, 99);
        assertThat(p.tickets()).containsEntry(BUS, 1);
    }

    @Test
    void aLobbyPlayerHasNoRoleNodeOrTickets() {
        Player p = new Player("Alice");
        assertThat(p.role()).isNull();
        assertThat(p.node()).isNull();
        assertThat(p.tickets()).isEmpty();
        assertThat(p.isMrX()).isFalse();
    }

    @Test
    void idAndTokenAreDifferentAndUnique() {
        Player a = new Player("A");
        Player b = new Player("B");
        assertThat(java.util.Set.of(a.id(), a.token(), b.id(), b.token())).hasSize(4);
    }
}
