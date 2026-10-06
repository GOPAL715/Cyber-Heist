package com.cyberheist.puzzle.provider;

import com.cyberheist.mission.MissionDifficulty;
import com.cyberheist.puzzle.PuzzleChallenge;
import com.cyberheist.puzzle.PuzzleProvider;
import com.cyberheist.puzzle.PuzzleType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Grid-pattern puzzles: a matrix of symbols with one cell missing.
 *
 * <p>Distinct from {@link SequencePuzzleProvider}, which varies along a single
 * axis. A pattern varies along two: rows follow a column rule and columns follow
 * a row rule, so the player has to read both directions to place the missing
 * symbol.
 *
 * <p>The rule is named in the prompt ("row cycles one column to the right,
 * column counts down by one") so the answer is unique rather than a guess about
 * which of many grid rules happens to fit.
 */
@Component
public class PatternPuzzleProvider implements PuzzleProvider {

    /** Symbols used in the grid, chosen to be visually unambiguous. */
    private static final String SYMBOLS = "ABCDEFGH";

    /** Options offered, including the correct one. */
    private static final int OPTION_COUNT = 4;

    @Override
    public PuzzleType type() {
        return PuzzleType.PATTERN;
    }

    @Override
    public PuzzleChallenge create(MissionDifficulty difficulty, long seed) {
        Random random = new Random(seed);
        int size = gridSize(difficulty);

        // A column rule of +rowStep mod |SYMBOLS| applied down a column, and the
        // matching row rule of -rowStep mod |SYMBOLS| applied along a row.
        int rowStep = 1 + random.nextInt(Math.max(1, SYMBOLS.length() - 2));
        int origin = random.nextInt(SYMBOLS.length());

        int[][] grid = new int[size][size];
        for (int row = 0; row < size; row++) {
            for (int column = 0; column < size; column++) {
                int value = Math.floorMod(origin + row * rowStep - column * rowStep, SYMBOLS.length());
                grid[row][column] = value;
            }
        }

        // The hole is always in the last row but never the last column, so a
        // whole row is always visible to read the rule from.
        int missingRow = size - 1;
        int missingColumn = random.nextInt(size - 1);
        int answerIndex = grid[missingRow][missingColumn];

        List<String> sequence = new ArrayList<>();
        for (int row = 0; row < size; row++) {
            StringBuilder line = new StringBuilder();
            for (int column = 0; column < size; column++) {
                if (column > 0) {
                    line.append(' ');
                }
                line.append(row == missingRow && column == missingColumn
                        ? "?"
                        : String.valueOf(SYMBOLS.charAt(grid[row][column])));
            }
            sequence.add(line.toString());
        }

        List<String> options = PuzzleGeneratorSupport.optionsWithAnswer(
                random,
                String.valueOf(SYMBOLS.charAt(answerIndex)),
                OPTION_COUNT,
                attempt -> {
                    int value = Math.floorMod(answerIndex + 1 + attempt, SYMBOLS.length());
                    return String.valueOf(SYMBOLS.charAt(value));
                });

        return new PuzzleChallenge(
                PuzzleType.PATTERN,
                "Complete the grid",
                "Each row shifts every symbol back one column, and each column "
                        + "counts down one symbol. Which symbol belongs in the gap?",
                sequence,
                options,
                String.valueOf(SYMBOLS.charAt(answerIndex)));
    }

    @Override
    public int timeLimitSeconds(MissionDifficulty difficulty) {
        return switch (difficulty) {
            case EASY -> 150;
            case MEDIUM -> 180;
            case HARD -> 240;
            case ELITE -> 300;
        };
    }

    /** A 3×3 grid is readable at a glance; 4×4 needs the harder tiers. */
    private int gridSize(MissionDifficulty difficulty) {
        return switch (difficulty) {
            case EASY, MEDIUM -> 3;
            case HARD, ELITE -> 4;
        };
    }
}