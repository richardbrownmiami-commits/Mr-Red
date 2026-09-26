package com.aibot;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.io.*;
import java.util.*;

public class MainActivity extends AppCompatActivity {

    private NeuralNetwork       nn;
    private Tokenizer           tokenizer;
    private NARSEngine          nars;
    private WeightManager       weightManager;
    private SelfLearner         selfLearner;
    private WebSearch           webSearch;
    private WebFetch            webFetch;
    private DatasetLoader       datasetLoader;
    private OnnxEngine          onnxEngine;
    private PersonalityEngine   personalityEngine;
    private EmotionSystem       emotionSystem;
    private UserMemory          userMemory;
    private ConversationManager convManager;
    private DeviceController    deviceController;
    private OverlayManager      overlayManager;
    private BirthStory          birthStory;

    private RecyclerView        chatRecycler;
    private ChatAdapter         chatAdapter;
    private EditText            inputField;
    private ImageButton         sendButton;
    private ImageButton         menuButton;
    private TextView            statusText;
    private ProgressBar         progressBar;

    private List<ChatMessage>   messages    = new ArrayList<>();
    private Handler             mainHandler = new Handler(Looper.getMainLooper());

    private static final int THINK_MIN = 500;
    private static final int THINK_MAX = 1500;
    private static final String TAG = "MainActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // CRASH LOGGER - writes to /sdcard/Android/data/com.aibot/files/crash.txt
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                File dir = getExternalFilesDir(null);
                if (dir == null) dir = getFilesDir();
                File f = new File(dir, "crash.txt");
                FileWriter w = new FileWriter(f, true);
                w.write("--- CRASH " + new Date() + " ---\n");
                w.write(Log.getStackTraceString(e));
                w.write("\n\n");
                w.close();
                Log.e(TAG, "Crash logged to " + f.getAbsolutePath(), e);
            } catch (Exception ex) {}
            // still show system crash dialog
            Log.e(TAG, "FATAL", e);
        });

        super.onCreate(savedInstanceState);
        try {
            setContentView(R.layout.activity_main);
            initUI();
            requestAllPermissions();
            initModules();
            checkSpecialPermissions();
        } catch (Exception e) {
            Log.e(TAG, "onCreate crash", e);
            try {
                Toast.makeText(this, "Init error: " + e.getMessage(), Toast.LENGTH_LONG).show();
            } catch (Exception ex) {}
        }
    }

    private void initUI() {
        try {
            chatRecycler = findViewById(R.id.chatRecycler);
            inputField   = findViewById(R.id.inputField);
            sendButton   = findViewById(R.id.sendButton);
            menuButton   = findViewById(R.id.menuButton);
            statusText   = findViewById(R.id.statusText);
            progressBar  = findViewById(R.id.progressBar);

            if (chatRecycler == null) {
                Log.e(TAG, "chatRecycler null - check activity_main.xml id");
                return;
            }
            chatAdapter = new ChatAdapter(messages);
            chatRecycler.setAdapter(chatAdapter);
            chatRecycler.setLayoutManager(new LinearLayoutManager(this));

            if (sendButton != null) sendButton.setOnClickListener(v -> onSendClicked());
            if (menuButton != null) menuButton.setOnClickListener(v -> showMenu());
            if (inputField != null) inputField.setOnEditorActionListener((v, id, e) -> { onSendClicked(); return true; });
        } catch (Exception e) {
            Log.e(TAG, "initUI crash", e);
        }
    }

    private void initModules() {
        new Thread(() -> {
            try {
               
