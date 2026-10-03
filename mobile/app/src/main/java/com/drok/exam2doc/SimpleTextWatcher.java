package com.drok.exam2doc;

import android.text.Editable;
import android.text.TextWatcher;

/** 极简 TextWatcher：只关心内容变化。 */
public class SimpleTextWatcher implements TextWatcher {
    private final Runnable onChange;

    public SimpleTextWatcher(Runnable onChange) {
        this.onChange = onChange;
    }

    @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
    @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }

    @Override public void afterTextChanged(Editable s) {
        onChange.run();
    }
}
