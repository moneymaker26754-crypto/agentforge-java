package io.github.moneymaker26754.agentforge.core;

import java.util.List;
import java.util.Optional;

public interface CheckpointStore {
    void append(SessionEvent event);

    List<SessionEvent> replay(SessionId sessionId);

    void saveSnapshot(SessionSnapshot snapshot);

    Optional<SessionSnapshot> latestSnapshot(SessionId sessionId);
}

