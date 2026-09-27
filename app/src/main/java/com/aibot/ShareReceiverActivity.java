package com.aibot;

import android.os.Bundle;
import android.widget.Toast;

public class ShareReceiverActivity extends MainActivity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            String text = getIntent() != null ? getIntent().getStringExtra("android.intent.extra.TEXT") : null;
            if (text != null && !text.trim().isEmpty()) {
                Toast.makeText(this, "Shared text received", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception ignored) {}
    }
}
