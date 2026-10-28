package com.course.tongxiao.ui;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.course.tongxiao.R;
import com.course.tongxiao.engine.challenge.CustomQuestionStore;
import com.course.tongxiao.engine.challenge.Question;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Manage the user's own challenge questions (design.md §5.4 "自定义题目").
 *
 * <p>Why this exists: the bundled bank is generic. A student who wants "我的高数公式"
 * or a couple's inside joke as the thing they must answer at 6 a.m. cannot get it from
 * an asset file, so the app lets them write their own — and those questions are drawn
 * first, so they actually meet them.
 */
public class CustomQuestionActivity extends AppCompatActivity {

    private CustomQuestionStore store;
    private TextView emptyView;
    private LinearLayout rows;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_custom_questions);
        com.course.tongxiao.ui.UiInsets.padSystemBars(findViewById(R.id.custom_questions_root));

        store = new CustomQuestionStore(this);
        emptyView = findViewById(R.id.custom_questions_empty);
        rows = findViewById(R.id.custom_questions_rows);

        MaterialToolbar toolbar = findViewById(R.id.custom_questions_toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        findViewById(R.id.custom_questions_add).setOnClickListener(v -> showAddDialog());

        render();
    }

    private void render() {
        List<Question> questions = store.load();
        rows.removeAllViews();
        emptyView.setVisibility(questions.isEmpty() ? View.VISIBLE : View.GONE);
        String[] letters = {"A", "B", "C", "D"};
        for (int i = 0; i < questions.size(); i++) {
            final int index = i;
            Question question = questions.get(i);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            int pad = Math.round(10 * getResources().getDisplayMetrics().density);
            row.setPadding(0, pad, 0, pad);

            TextView title = new TextView(this);
            title.setText(String.format(Locale.CHINA, "%d. %s", i + 1, question.text));
            title.setTextSize(15f);
            title.setTextColor(getColor(R.color.ink));
            row.addView(title);

            TextView answer = new TextView(this);
            answer.setText(getString(R.string.custom_question_answer_line,
                    letters[question.correctIndex], question.options.get(question.correctIndex)));
            answer.setTextSize(12f);
            answer.setTextColor(getColor(R.color.muted));
            row.addView(answer);

            com.google.android.material.button.MaterialButton delete =
                    new com.google.android.material.button.MaterialButton(this, null,
                            com.google.android.material.R.attr.materialButtonOutlinedStyle);
            delete.setText(R.string.custom_questions_delete);
            delete.setTextSize(12f);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, Math.round(40 * getResources()
                    .getDisplayMetrics().density));
            params.topMargin = Math.round(4 * getResources().getDisplayMetrics().density);
            delete.setLayoutParams(params);
            delete.setOnClickListener(v -> confirmDelete(index, question.text));
            row.addView(delete);

            View divider = new View(this);
            divider.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, Math.round(
                    getResources().getDisplayMetrics().density * 0.5f))));
            divider.setBackgroundColor(getColor(R.color.line));

            rows.addView(row);
            rows.addView(divider);
        }
    }

    private void showAddDialog() {
        if (store.size() >= CustomQuestionStore.MAX_QUESTIONS) {
            toast(R.string.custom_questions_full);
            return;
        }
        View content = LayoutInflater.from(this).inflate(R.layout.dialog_custom_question, null);
        EditText text = content.findViewById(R.id.custom_question_text);
        EditText option1 = content.findViewById(R.id.custom_option_1);
        EditText option2 = content.findViewById(R.id.custom_option_2);
        EditText option3 = content.findViewById(R.id.custom_option_3);
        EditText option4 = content.findViewById(R.id.custom_option_4);

        // The four "correct answer" radios sit on the option rows (no RadioGroup, so
        // exclusivity is wired here); this keeps the marker reachable while typing.
        RadioButton[] correctRadios = {
                content.findViewById(R.id.custom_correct_1),
                content.findViewById(R.id.custom_correct_2),
                content.findViewById(R.id.custom_correct_3),
                content.findViewById(R.id.custom_correct_4)};
        wireCorrectRadios(correctRadios);

        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.custom_questions_add)
                .setView(content)
                .setNegativeButton(R.string.dialog_cancel, null)
                .setPositiveButton(R.string.dialog_ok, (dialogInterface, which) -> {
                    List<String> options = new ArrayList<>();
                    options.add(valueOf(option1));
                    options.add(valueOf(option2));
                    options.add(valueOf(option3));
                    options.add(valueOf(option4));
                    int correctIndex = checkedIndexOf(correctRadios);
                    if (!store.add(valueOf(text), options, correctIndex)) {
                        // One message for every rejection reason keeps it honest: the
                        // store is the single validator, the UI does not re-implement it.
                        toast(R.string.custom_questions_invalid);
                        return;
                    }
                    toast(R.string.custom_questions_saved);
                    render();
                })
                .create();
        // Five text fields open a keyboard that would otherwise cover 确定/取消: let the
        // dialog resize around the IME so the buttons stay reachable while typing.
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(
                    android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        dialog.show();
    }

    private void confirmDelete(int index, String text) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.custom_questions_delete)
                .setMessage(getString(R.string.custom_questions_delete_confirm, text))
                .setNegativeButton(R.string.dialog_cancel, null)
                .setPositiveButton(R.string.custom_questions_delete, (dialog, which) -> {
                    store.removeAt(index);
                    render();
                })
                .show();
    }

    private static String valueOf(EditText field) {
        return field.getText() == null ? "" : field.getText().toString().trim();
    }

    /**
     * Makes the four inline answer radios mutually exclusive.
     *
     * <p>Package-private on purpose: the layout is inflated directly by tests, and the
     * exclusivity must be the same code path in production and in tests (a RadioGroup
     * cannot be used because each radio sits inside its own option row).
     */
    static void wireCorrectRadios(@NonNull RadioButton[] radios) {
        for (int i = 0; i < radios.length; i++) {
            final int position = i;
            radios[i].setOnClickListener(v -> {
                for (int j = 0; j < radios.length; j++) {
                    radios[j].setChecked(j == position);
                }
            });
        }
    }

    private static int checkedIndexOf(RadioButton[] radios) {        for (int i = 0; i < radios.length; i++) {
            if (radios[i] != null && radios[i].isChecked()) {
                return i;
            }
        }
        return 0; // "A" stays the default when nothing was tapped
    }

    private void toast(int messageRes) {
        Toast.makeText(this, messageRes, Toast.LENGTH_SHORT).show();
    }
}
