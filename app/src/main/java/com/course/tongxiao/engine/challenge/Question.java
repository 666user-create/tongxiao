package com.course.tongxiao.engine.challenge;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

/** One multiple-choice question (design.md §5.4). Immutable once built. */
public class Question {

    public enum Type { ARITHMETIC, GENERAL, LOGIC }

    public final String text;
    public final List<String> options;
    public final int correctIndex;
    public final Type type;
    public final int difficulty;

    public Question(@NonNull String text, @NonNull List<String> options,
                    int correctIndex, @NonNull Type type, int difficulty) {
        if (options.size() < 2) {
            throw new IllegalArgumentException("a question needs at least 2 options");
        }
        if (correctIndex < 0 || correctIndex >= options.size()) {
            throw new IllegalArgumentException("correctIndex out of range");
        }
        this.text = text;
        this.options = new ArrayList<>(options);
        this.correctIndex = correctIndex;
        this.type = type;
        this.difficulty = Math.max(1, Math.min(3, difficulty));
    }

    public boolean isCorrect(int chosenIndex) {
        return chosenIndex == correctIndex;
    }
}
