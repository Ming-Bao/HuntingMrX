package com.huntingmrxwellington.repository;

import com.huntingmrxwellington.model.GameSession;
import org.springframework.stereotype.Repository;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Repository
public class GameRepository {
    private final ConcurrentHashMap<String, GameSession> store = new ConcurrentHashMap<>();

    public GameSession save(GameSession session) {
        store.put(session.getId(), session);
        return session;
    }

    public Optional<GameSession> findById(String id) {
        return Optional.ofNullable(store.get(id));
    }

    public Optional<GameSession> findByJoinCode(String joinCode) {
        return store.values().stream()
                .filter(s -> joinCode.equals(s.getJoinCode()))
                .findFirst();
    }

    public void delete(String id) {
        store.remove(id);
    }

    public Iterable<GameSession> findAll() {
        return store.values();
    }
}
