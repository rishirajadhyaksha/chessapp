package chess.game;

import chess.model.*;
import chess.ai.*;
import org.springframework.web.socket.WebSocketSession;

import java.util.*;
import java.util.concurrent.*;

public class GameSession {

    public enum Mode { PVP, PVA }
    public enum AiDifficulty { EASY, MEDIUM, HARD, MASTER }

    private final String gameId;
    private final Board board;
    private final Mode mode;
    private final AiDifficulty difficulty;

    // For PvP: two sessions. For PvA: one session (white=human, black=AI)
    private WebSocketSession whiteSession;
    private WebSocketSession blackSession;

    private ChessAI basicAI;
    private StrongChessAI strongAI;

    private volatile boolean aiThinking = false;
    private final ExecutorService aiExecutor = Executors.newSingleThreadExecutor();

    public GameSession(String gameId, Mode mode, AiDifficulty difficulty) {
        this.gameId = gameId;
        this.board = new Board();
        this.mode = mode;
        this.difficulty = difficulty;

        if (mode == Mode.PVA) {
            switch (difficulty) {
                case EASY   -> basicAI = new ChessAI(Color.BLACK, 1);
                case MEDIUM -> basicAI = new ChessAI(Color.BLACK, 3);
                case HARD   -> basicAI = new ChessAI(Color.BLACK, 5);
                case MASTER -> strongAI = new StrongChessAI(Color.BLACK);
            }
        }
    }

    public String getGameId() { return gameId; }
    public Board getBoard() { return board; }
    public Mode getMode() { return mode; }
    public AiDifficulty getDifficulty() { return difficulty; }
    public boolean isAiThinking() { return aiThinking; }

    public void setWhiteSession(WebSocketSession s) { this.whiteSession = s; }
    public void setBlackSession(WebSocketSession s) { this.blackSession = s; }
    public WebSocketSession getWhiteSession() { return whiteSession; }
    public WebSocketSession getBlackSession() { return blackSession; }

    public boolean isFull() {
        if (mode == Mode.PVA) return whiteSession != null;
        return whiteSession != null && blackSession != null;
    }

    /** Returns the color assigned to a given WebSocket session. */
    public Optional<Color> colorOf(WebSocketSession session) {
        if (session.equals(whiteSession)) return Optional.of(Color.WHITE);
        if (session.equals(blackSession)) return Optional.of(Color.BLACK);
        return Optional.empty();
    }

    /**
     * Attempt to make a move. Returns true if the move was legal and applied.
     */
    public synchronized boolean applyMove(int fromRow, int fromCol, int toRow, int toCol, String promotionPiece) {
        List<Move> legal = board.getLegalMovesForPiece(fromRow, fromCol);
        for (Move m : legal) {
            if (m.getToRow() == toRow && m.getToCol() == toCol) {
                // Handle promotion
                if (m.isPromotion() && promotionPiece != null) {
                    Piece promo = makePromoPiece(promotionPiece, board.getCurrentTurn());
                    Move promMove = new Move(fromRow, fromCol, toRow, toCol,
                            m.getPiece(), m.getCapturedPiece(),
                            false, false, false, true, promo);
                    board.makeMove(promMove);
                } else {
                    board.makeMove(m);
                }
                return true;
            }
        }
        return false;
    }

    /** Schedule an AI move asynchronously; calls callback when done. */
    public void scheduleAiMove(Runnable afterMove) {
        if (aiThinking) return;
        aiThinking = true;
        aiExecutor.submit(() -> {
            try {
                Move best;
                if (strongAI != null) {
                    best = strongAI.findBestMove(board);
                } else if (basicAI != null) {
                    best = basicAI.findBestMove(board);
                } else {
                    return;
                }
                if (best != null) {
                    synchronized (this) {
                        board.makeMove(best);
                    }
                }
            } finally {
                aiThinking = false;
                afterMove.run();
            }
        });
    }

    /** Serialize the full board to a DTO for sending over WebSocket. */
    public BoardStateDTO toBoardState(String event) {
        BoardStateDTO dto = new BoardStateDTO();
        dto.event = event;
        dto.gameId = gameId;
        dto.turn = board.getCurrentTurn().name();
        dto.gameState = board.getGameState().name();
        dto.aiThinking = aiThinking;
        dto.mode = mode.name();

        // 8x8 board of pieces
        dto.board = new String[8][8];
        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                Piece p = board.getPiece(r, c);
                dto.board[r][c] = p == null ? null : p.getColor().name() + "_" + p.getSymbol();
            }
        }

        // Move history as algebraic-style strings
        dto.moveHistory = board.getMoveHistory().stream()
                .map(Move::toString)
                .toList();

        return dto;
    }

    public List<int[]> getLegalMoves(int row, int col) {
        return board.getLegalMovesForPiece(row, col).stream()
                .map(m -> new int[]{m.getToRow(), m.getToCol()})
                .toList();
    }

    private Piece makePromoPiece(String symbol, Color color) {
        return switch (symbol.toUpperCase()) {
            case "Q" -> new chess.model.Queen(color);
            case "R" -> new chess.model.Rook(color);
            case "B" -> new chess.model.Bishop(color);
            case "N" -> new chess.model.Knight(color);
            default  -> new chess.model.Queen(color);
        };
    }

    public void shutdown() {
        aiExecutor.shutdownNow();
    }

    // DTO inner class
    public static class BoardStateDTO {
        public String event;
        public String gameId;
        public String turn;
        public String gameState;
        public boolean aiThinking;
        public String mode;
        public String[][] board;
        public List<String> moveHistory;
    }
}
