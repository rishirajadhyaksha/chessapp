package chess.server;

import chess.game.*;
import chess.model.Color;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.*;

@Component
public class ChessWebSocketHandler extends TextWebSocketHandler {

    private final GameRegistry registry;
    private final ObjectMapper mapper = new ObjectMapper();

    public ChessWebSocketHandler(GameRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        // Client must send a "join" message to start
        sendJson(session, Map.of("event", "connected", "sessionId", session.getId()));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        JsonNode msg = mapper.readTree(message.getPayload());
        String action = msg.get("action").asText();

        switch (action) {
            case "join_pva"  -> handleJoinPvA(session, msg);
            case "join_pvp"  -> handleJoinPvP(session);
            case "move"      -> handleMove(session, msg);
            case "legal"     -> handleLegal(session, msg);
            case "new_game"  -> handleNewGame(session);
            default          -> sendError(session, "Unknown action: " + action);
        }
    }

    // ── Join: Player vs AI ────────────────────────────────────────────────────

    private void handleJoinPvA(WebSocketSession session, JsonNode msg) {
        String diff = msg.has("difficulty") ? msg.get("difficulty").asText() : "MEDIUM";
        GameSession.AiDifficulty difficulty;
        try {
            difficulty = GameSession.AiDifficulty.valueOf(diff.toUpperCase());
        } catch (Exception e) {
            difficulty = GameSession.AiDifficulty.MEDIUM;
        }

        GameSession game = registry.createGame(GameSession.Mode.PVA, difficulty);
        game.setWhiteSession(session);
        registry.registerSocket(session.getId(), game.getGameId());

        sendJson(session, Map.of(
                "event", "joined",
                "gameId", game.getGameId(),
                "color", "WHITE",
                "mode", "PVA",
                "difficulty", difficulty.name()
        ));
        broadcast(game, game.toBoardState("board_update"));
    }

    // ── Join: Player vs Player ────────────────────────────────────────────────

    private void handleJoinPvP(WebSocketSession session) {
        // Try to find a waiting game first
        Optional<GameSession> waiting = registry.findWaitingPvP();
        GameSession game;
        String assignedColor;

        if (waiting.isPresent()) {
            game = waiting.get();
            game.setBlackSession(session);
            registry.registerSocket(session.getId(), game.getGameId());
            assignedColor = "BLACK";

            sendJson(session, Map.of(
                    "event", "joined",
                    "gameId", game.getGameId(),
                    "color", "BLACK",
                    "mode", "PVP"
            ));
            // Tell white that the game is starting
            sendJson(game.getWhiteSession(), Map.of(
                    "event", "opponent_joined",
                    "gameId", game.getGameId()
            ));
            broadcast(game, game.toBoardState("board_update"));
        } else {
            game = registry.createGame(GameSession.Mode.PVP, null);
            game.setWhiteSession(session);
            registry.registerSocket(session.getId(), game.getGameId());
            assignedColor = "WHITE";

            sendJson(session, Map.of(
                    "event", "joined",
                    "gameId", game.getGameId(),
                    "color", "WHITE",
                    "mode", "PVP",
                    "waiting", true
            ));
        }
    }

    // ── Move ─────────────────────────────────────────────────────────────────

    private void handleMove(WebSocketSession session, JsonNode msg) {
        Optional<GameSession> gameOpt = registry.getGameForSocket(session.getId());
        if (gameOpt.isEmpty()) { sendError(session, "Not in a game"); return; }
        GameSession game = gameOpt.get();

        // Verify it's this player's turn
        Optional<Color> colorOpt = game.colorOf(session);
        if (colorOpt.isEmpty()) { sendError(session, "Not a player"); return; }
        Color playerColor = colorOpt.get();

        if (game.getBoard().getCurrentTurn() != playerColor) {
            sendError(session, "Not your turn");
            return;
        }
        if (game.isAiThinking()) {
            sendError(session, "AI is thinking");
            return;
        }

        int fromRow = msg.get("fromRow").asInt();
        int fromCol = msg.get("fromCol").asInt();
        int toRow   = msg.get("toRow").asInt();
        int toCol   = msg.get("toCol").asInt();
        String promo = msg.has("promotion") ? msg.get("promotion").asText() : null;

        boolean applied = game.applyMove(fromRow, fromCol, toRow, toCol, promo);
        if (!applied) {
            sendError(session, "Illegal move");
            return;
        }

        broadcast(game, game.toBoardState("board_update"));

        // If PvA and game still going, trigger AI
        if (game.getMode() == GameSession.Mode.PVA &&
                game.getBoard().getGameState().name().equals("PLAYING") ||
                game.getMode() == GameSession.Mode.PVA &&
                        game.getBoard().getGameState().name().equals("CHECK")) {

            broadcast(game, game.toBoardState("ai_thinking"));
            game.scheduleAiMove(() -> broadcast(game, game.toBoardState("board_update")));
        }
    }

    // ── Legal moves for a square ──────────────────────────────────────────────

    private void handleLegal(WebSocketSession session, JsonNode msg) {
        Optional<GameSession> gameOpt = registry.getGameForSocket(session.getId());
        if (gameOpt.isEmpty()) return;
        GameSession game = gameOpt.get();

        int row = msg.get("row").asInt();
        int col = msg.get("col").asInt();
        List<int[]> moves = game.getLegalMoves(row, col);

        List<Map<String, Integer>> result = moves.stream()
                .map(m -> Map.of("row", m[0], "col", m[1]))
                .toList();

        sendJson(session, Map.of("event", "legal_moves", "row", row, "col", col, "moves", result));
    }

    // ── New game (rematch) ────────────────────────────────────────────────────

    private void handleNewGame(WebSocketSession session) {
        Optional<GameSession> gameOpt = registry.getGameForSocket(session.getId());
        if (gameOpt.isEmpty()) return;
        GameSession old = gameOpt.get();

        old.getBoard().reset();
        broadcast(old, old.toBoardState("board_update"));
    }

    // ── Connection closed ─────────────────────────────────────────────────────

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Optional<GameSession> gameOpt = registry.getGameForSocket(session.getId());
        gameOpt.ifPresent(game -> {
            // Notify opponent
            Optional<Color> color = game.colorOf(session);
            color.ifPresent(c -> {
                WebSocketSession opponent = c == Color.WHITE
                        ? game.getBlackSession()
                        : game.getWhiteSession();
                if (opponent != null && opponent.isOpen()) {
                    sendJson(opponent, Map.of("event", "opponent_disconnected"));
                }
            });
        });
        registry.removeSocket(session.getId());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void broadcast(GameSession game, Object payload) {
        String json;
        try { json = mapper.writeValueAsString(payload); } catch (Exception e) { return; }

        sendRaw(game.getWhiteSession(), json);
        if (game.getMode() == GameSession.Mode.PVP) {
            sendRaw(game.getBlackSession(), json);
        }
    }

    private void sendJson(WebSocketSession session, Object payload) {
        try {
            String json = mapper.writeValueAsString(payload);
            sendRaw(session, json);
        } catch (Exception e) {
            // ignore
        }
    }

    private synchronized void sendRaw(WebSocketSession session, String json) {
        if (session != null && session.isOpen()) {
            try { session.sendMessage(new TextMessage(json)); }
            catch (IOException e) { /* ignore */ }
        }
    }

    private void sendError(WebSocketSession session, String msg) {
        sendJson(session, Map.of("event", "error", "message", msg));
    }
}
