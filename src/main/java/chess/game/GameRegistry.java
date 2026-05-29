package chess.game;

import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class GameRegistry {

    private final Map<String, GameSession> sessions = new ConcurrentHashMap<>();
    // Map websocket session id -> game id
    private final Map<String, String> socketToGame = new ConcurrentHashMap<>();

    public GameSession createGame(GameSession.Mode mode, GameSession.AiDifficulty difficulty) {
        String id = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        GameSession game = new GameSession(id, mode, difficulty);
        sessions.put(id, game);
        return game;
    }

    public Optional<GameSession> findById(String gameId) {
        return Optional.ofNullable(sessions.get(gameId));
    }

    /** Find an open PvP game waiting for a second player, or null. */
    public Optional<GameSession> findWaitingPvP() {
        return sessions.values().stream()
                .filter(g -> g.getMode() == GameSession.Mode.PVP && !g.isFull())
                .findFirst();
    }

    public void registerSocket(String socketId, String gameId) {
        socketToGame.put(socketId, gameId);
    }

    public Optional<GameSession> getGameForSocket(String socketId) {
        String gameId = socketToGame.get(socketId);
        if (gameId == null) return Optional.empty();
        return findById(gameId);
    }

    public void removeSocket(String socketId) {
        String gameId = socketToGame.remove(socketId);
        if (gameId != null) {
            GameSession g = sessions.get(gameId);
            if (g != null) {
                // If no sessions left, clean up the game
                boolean whiteGone = g.getWhiteSession() == null || !g.getWhiteSession().isOpen();
                boolean blackGone = g.getBlackSession() == null || !g.getBlackSession().isOpen();
                if (whiteGone && blackGone) {
                    g.shutdown();
                    sessions.remove(gameId);
                }
            }
        }
    }

    public int activeGameCount() {
        return sessions.size();
    }
}
