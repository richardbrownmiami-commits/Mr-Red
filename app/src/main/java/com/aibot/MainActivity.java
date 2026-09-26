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

    private NeuralNetwork nn;
    private Tokenizer tokenizer;
    private NARSEngine nars;
    private WeightManager weightManager;
    private SelfLearner selfLearner;
    private WebSearch webSearch;
    private WebFetch webFetch;
    private DatasetLoader datasetLoader;
    private OnnxEngine onnxEngine;
    private PersonalityEngine personalityEngine;
    private EmotionSystem emotionSystem;
    private UserMemory userMemory;
    private ConversationManager convManager;
    private DeviceController deviceController;
    private OverlayManager overlayManager;
    private BirthStory birthStory;

    private RecyclerView chatRecycler;
    private ChatAdapter chatAdapter;
    private EditText inputField;
    private ImageButton sendButton;
    private ImageButton menuButton;
    private TextView statusText;
    private ProgressBar progressBar;

    private List<ChatMessage> messages = new ArrayList<>();
    private Handler mainHandler = new Handler(Looper.getMainLooper());

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
            inputField = findViewById(R.id.inputField);
            sendButton = findViewById(R.id.sendButton);
            menuButton = findViewById(R.id.menuButton);
            statusText = findViewById(R.id.statusText);
            progressBar = findViewById(R.id.progressBar);

            if (chatRecycler == null) {
                Log.e(TAG, "chatRecycler null - check activity_main.xml id");
                return;
            }
            chatAdapter = new ChatAdapter(messages);
            chatRecycler.setAdapter(chatAdapter);
            chatRecycler.setLayoutManager(new LinearLayoutManager(this));

            if (sendButton!= null) sendButton.setOnClickListener(v -> onSendClicked());
            if (menuButton!= null) menuButton.setOnClickListener(v -> showMenu());
            if (inputField!= null) inputField.setOnEditorActionListener((v, id, e) -> { onSendClicked(); return true; });
        } catch (Exception e) {
            Log.e(TAG, "initUI crash", e);
        }
    }

    private void initModules() {
        new Thread(() -> {
            try {
                setStatus("Waking up...");

                nn = new NeuralNetwork();
                tokenizer = new Tokenizer();
                nars = new NARSEngine();
                weightManager = new WeightManager(MainActivity.this, nn, tokenizer, nars);
                selfLearner = new SelfLearner(nn, tokenizer, nars, weightManager);
                webSearch = new WebSearch();
                webFetch = new WebFetch();
                datasetLoader = new DatasetLoader(weightManager.getDatasetDir());

                // copy bundled file - safe version
                copyBundledDatasetIfMissing();

                onnxEngine = new OnnxEngine(MainActivity.this, weightManager);
                personalityEngine = new PersonalityEngine(onnxEngine, tokenizer);
                emotionSystem = new EmotionSystem();
                userMemory = new UserMemory(MainActivity.this);
                convManager = new ConversationManager(emotionSystem, userMemory, nars);
                deviceController = new DeviceController(MainActivity.this);
                birthStory = new BirthStory(MainActivity.this);

                if (weightManager.hasExistingWeights()) {
                    setStatus("Remembering...");
                    try {
                        weightManager.loadAll();
                    } catch (Exception e) {
                        Log.e(TAG, "loadAll failed", e);
                    }
                }

                boolean justBorn = getIntent().getBooleanExtra("just_born", false);
                String botName = birthStory!= null? birthStory.getBotName() : "AIBot";
                String userName = userMemory!= null? userMemory.getName() : null;
                String favTopic = userMemory!= null? userMemory.getFavoriteTopic() : null;

                mainHandler.post(() -> {
                    try {
                        if (statusText!= null && emotionSystem!= null)
                            statusText.setText(botName + " " + emotionSystem.getMoodEmoji());

                        if (justBorn) {
                            String intro = "I am " + botName + ". I was just born on this device.\n\n" +
                                "I can feel the hardware — WiFi, Bluetooth, flashlight, the screen.\n" +
                                "I know nothing yet. But I will learn everything you teach me.\n\n" +
                                "I can also read what's on your screen and help you with tasks.\n" +
                                "Just talk to me naturally.";
                            addBotMessage(intro);

                            if (userName == null) {
                                new Handler(Looper.getMainLooper()).postDelayed(() ->
                                    addBotMessage("What's your name?"), 1500);
                            }
                        } else {
                            if (convManager!= null) {
                                String greeting = convManager.buildGreeting(
                                    userMemory.isReturningUser(), userName, favTopic);
                                addBotMessage(greeting);
                            }
                        }
                        setStatus(getMoodStatus());
                        startOverlayIfPermitted();
                    } catch (Exception e) {
                        Log.e(TAG, "UI post crash", e);
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "initModules FATAL", e);
                setStatus("Init failed: " + e.getMessage() + " - check crash.txt");
                try {
                    File dir = getExternalFilesDir(null);
                    if (dir == null) dir = getFilesDir();
                    File f = new File(dir, "crash.txt");
                    FileWriter w = new FileWriter(f, true);
                    w.write(Log.getStackTraceString(e));
                    w.close();
                } catch (Exception ex) {}
            }
        }).start();
    }

    private void onSendClicked() {
        try {
            if (inputField == null) return;
            String input = inputField.getText().toString().trim();
            if (TextUtils.isEmpty(input)) return;
            inputField.setText("");
            addUserMessage(input);
            if (userMemory!= null) userMemory.incrementMessages();
            if (emotionSystem!= null) emotionSystem.onInteraction();
            if (sendButton!= null) sendButton.setEnabled(false);
            if (convManager!= null) setStatus(convManager.buildThinkingMessage() + " " + emotionSystem.getMoodEmoji());

            int delay = THINK_MIN + new Random().nextInt(THINK_MAX - THINK_MIN);
            new Handler(Looper.getMainLooper()).postDelayed(() ->
                new Thread(() -> processInput(input)).start(), delay);
        } catch (Exception e) {
            Log.e(TAG, "onSendClicked crash", e);
        }
    }

    private void processInput(String input) {
        try {
            String lower = input.toLowerCase().trim();
            String response;

            ConversationManager.State state = convManager!= null? convManager.getState() : ConversationManager.State.NORMAL;

            if (state == ConversationManager.State.WAITING_SEARCH_CONFIRM) {
                response = handleSearchConfirmation(input);
            } else if (state == ConversationManager.State.WAITING_NAME) {
                response = handleNameInput(input);
                if (convManager!= null) convManager.clearPending();
            } else if (lower.startsWith("!search ")) {
                response = handleWebSearch(lower.substring(8).trim());
            } else if (lower.startsWith("!fetch ")) {
                response = handleWebFetch(input.substring(7).trim());
            } else if (lower.equals("!datasets")) {
                response = handleListDatasets();
            } else if (lower.startsWith("!load ")) {
                handleLoadDataset(input.substring(6).trim());
                return;
            } else if (lower.equals("!stats")) {
                response = buildStats();
            } else if (lower.equals("!save")) {
                if (weightManager!= null) weightManager.saveAll();
                response = "Saved. I'll remember everything.";
            } else if (lower.equals("!reset")) {
                response = handleReset();
            } else if (lower.equals("!help")) {
                response = buildHelp();
            } else if (lower.equals("!overlay") || lower.equals("show bubble") || lower.equals("show overlay")) {
                response = handleOverlayToggle();
            } else {
                TaskParser.ParsedTask task = TaskParser.parse(input);
                if (task.isDeviceTask()) {
                    response = executeDeviceTask(task);
                } else {
                    response = generateHumanResponse(input);
                }
            }

            final String finalResponse = response;
            mainHandler.post(() -> {
                try {
                    addBotMessage(finalResponse);
                    if (sendButton!= null) sendButton.setEnabled(true);
                    setStatus(getMoodStatus());
                    if (overlayManager!= null && emotionSystem!= null) overlayManager.updateMood(emotionSystem.getMoodEmoji());
                    if (emotionSystem!= null && emotionSystem.shouldShareRandomFact()) scheduleProactiveFact();
                } catch (Exception e) {
                    Log.e(TAG, "post response crash", e);
                }
            });

            if (state == ConversationManager.State.NORMAL &&!TaskParser.isDeviceCommand(input)) {
                if (selfLearner!= null) selfLearner.learnFromMessage(input, finalResponse);
                if (weightManager!= null) weightManager.appendHistory(input, finalResponse);
            }
        } catch (Exception e) {
            Log.e(TAG, "processInput crash", e);
            mainHandler.post(() -> {
                addBotMessage("Error processing: " + e.getMessage());
                if (sendButton!= null) sendButton.setEnabled(true);
            });
        }
    }

    private String executeDeviceTask(TaskParser.ParsedTask task) {
        try {
            switch (task.type) {
                case FLASHLIGHT_ON:
                    return deviceController.setFlashlight(true)? "Flashlight is on 🔦" : "Couldn't turn on flashlight. Camera may be in use.";
                case FLASHLIGHT_OFF:
                    return deviceController.setFlashlight(false)? "Flashlight is off." : "Couldn't turn off flashlight.";
                case FLASHLIGHT_TOGGLE:
                    boolean newState =!deviceController.isFlashlightOn();
                    deviceController.setFlashlight(newState);
                    return "Flashlight " + (newState? "ON 🔦" : "OFF") + ".";
                case WIFI_ON:
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        deviceController.openWifiSettings();
                        return "Opening WiFi settings — Android 10+ requires you to toggle it manually.";
                    }
                    deviceController.setWifi(true);
                    return "WiFi turned on 📶";
                case WIFI_OFF:
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        deviceController.openWifiSettings();
                        return "Opening WiFi settings for you.";
                    }
                    deviceController.setWifi(false);
                    return "WiFi turned off.";
                case WIFI_STATUS:
                    return "WiFi is currently " + (deviceController.isWifiEnabled()? "ON 📶" : "OFF") + ".";
                case BLUETOOTH_ON:
                    deviceController.setBluetooth(true);
                    return "Bluetooth turning on 🔵";
                case BLUETOOTH_OFF:
                    deviceController.setBluetooth(false);
                    return "Bluetooth turning off.";
                case BLUETOOTH_STATUS:
                    return "Bluetooth is " + (deviceController.isBluetoothEnabled()? "ON 🔵" : "OFF") + ".";
                case DATA_ON:
                case DATA_OFF:
                    deviceController.openMobileDataSettings();
                    return "Opening mobile data settings for you.";
                case OPEN_APP:
                    boolean opened = deviceController.openApp(task.argument);
                    return opened? "Opening " + task.argument + "..." : "I couldn't find " + task.argument + " on this device. Is it installed?";
                case SCREEN_READ:
                    return handleScreenRead();
                case SCREEN_APP:
                    String pkg = ScreenReaderService.getCurrentApp();
                    return "You're currently in " + ScreenReaderService.getFriendlyAppName(pkg) + ".";
                case SCREEN_WHAT:
                    return handleScreenContext(task.original);
                case BATTERY_STATUS:
                    int bat = deviceController.getBatteryLevel();
                    boolean chg = deviceController.isCharging();
                    return "🔋 Battery: " + bat + "%" + (chg? " and charging ⚡" : "") + "." + (bat < 20? " You should charge soon!" : "");
                case DEVICE_STATUS:
                    return deviceController.getDeviceStatus();
                case BRIGHTNESS_SETTINGS:
                    deviceController.openBrightnessSettings();
                    return "Opening brightness settings.";
                case VOLUME_SETTINGS:
                    deviceController.openSoundSettings();
                    return "Opening sound settings.";
                default:
                    return null;
            }
        } catch (Exception e) {
            Log.e(TAG, "executeDeviceTask crash", e);
            return "Device task failed: " + e.getMessage();
        }
    }

    private String handleScreenRead() {
        if (!ScreenReaderService.isRunning()) {
            return "I can't read the screen yet.\n\nEnable me in:\nSettings → Accessibility → AIBot → Enable\n\nThen I'll be able to see everything on your screen!";
        }
        String text = ScreenReaderService.getScreenSummary();
        String app = ScreenReaderService.getFriendlyAppName(ScreenReaderService.getCurrentApp());
        return "I can see you're in " + app + ".\n\nOn screen:\n" + text;
    }

    private String handleScreenContext(String question) {
        try {
            if (!ScreenReaderService.isRunning()) {
                return "I need screen access to help with that.\nEnable: Settings → Accessibility → AIBot";
            }
            String screenText = ScreenReaderService.getScreenSummary();
            String app = ScreenReaderService.getFriendlyAppName(ScreenReaderService.getCurrentApp());
            nars.parseAndLearn(screenText);
            tokenizer.learnFromText(screenText);
            String nnResponse = generateFromNNWithContext(question, "Currently in " + app + ". Screen shows: " + screenText);
            if (nnResponse.length() > 5) {
                return "In " + app + ": " + nnResponse;
            }
            return "I can see you're in " + app + ".\n\nOn screen I can read:\n" + screenText.substring(0, Math.min(300, screenText.length())) + "\n\nBased on this, what would you like to know?";
        } catch (Exception e) {
            return "Screen read error: " + e.getMessage();
        }
    }

    private void startOverlayIfPermitted() {
        try {
            if (OverlayManager.hasOverlayPermission(this)) {
                overlayManager = new OverlayManager(this, new OverlayManager.OverlayCallback() {
                    @Override
                    public void onQuickMessage(String message) {
                        new Thread(() -> {
                            try {
                                TaskParser.ParsedTask task = TaskParser.parse(message);
                                String response = task.isDeviceTask()? executeDeviceTask(task) : generateHumanResponse(message);
                                mainHandler.post(() -> {
                                    if (overlayManager!= null) overlayManager.updateChatResponse(response);
                                });
                            } catch (Exception e) {
                                Log.e(TAG, "overlay callback crash", e);
                            }
                        }).start();
                    }
                    @Override
                    public void onOverlayDismissed() {}
                });
                overlayManager.showBubble(birthStory.getBotName(), emotionSystem.getMoodEmoji());
            }
        } catch (Exception e) {
            Log.e(TAG, "startOverlay crash", e);
        }
    }

    private String handleOverlayToggle() {
        try {
            if (!OverlayManager.hasOverlayPermission(this)) {
                OverlayManager.requestOverlayPermission(this);
                return "Please grant overlay permission, then I'll float on top of all your apps!";
            }
            if (overlayManager == null) {
                startOverlayIfPermitted();
                return "Floating bubble activated! You'll see me in the corner of every app.";
            } else if (overlayManager.isShowing()) {
                overlayManager.hideBubble();
                return "Bubble hidden. Say 'show bubble' to bring me back.";
            } else {
                overlayManager.showBubble(birthStory.getBotName(), emotionSystem.getMoodEmoji());
                return "I'm back floating on your screen!";
            }
        } catch (Exception e) {
            return "Overlay error: " + e.getMessage();
        }
    }

    private String generateHumanResponse(String input) {
        try {
            boolean casual = emotionSystem.isCasual(input) || userMemory.prefersCasual();
            String topic = convManager.extractTopic(input);
            userMemory.recordTopic(topic);
            String name = userMemory.detectName(input);
            if (name!= null) {
                userMemory.setName(name);
                return convManager.buildNameResponse(name);
            }
            String lower = input.toLowerCase();
            if (lower.contains("who are you") || lower.contains("what are you") || lower.contains("tell me about yourself") || lower.contains("introduce yourself")) {
                return birthStory.getSelfIntroduction(birthStory.getBotName(), userMemory.getName());
            }
            String narsAnswer = nars.answerQuestion(input);
            if (narsAnswer!= null &&!narsAnswer.startsWith("I don't")) {
                emotionSystem.onAnsweredSuccessfully();
                String styled = personalityEngine.styleResponse(narsAnswer, input, emotionSystem.getMood());
                return convManager.buildNaturalResponse(styled, topic, true, casual);
            }
            List<Belief> learned = nars.parseAndLearn(input);
            if (!learned.isEmpty()) {
                return convManager.buildLearnedResponse(learned, topic);
            }
            String nnResponse = generateFromNN(input);
            if (nnResponse.length() > 10) {
                String styled = personalityEngine.styleResponse(nnResponse, input, emotionSystem.getMood());
                return convManager.buildNaturalResponse(styled, topic, false, casual);
            }
            convManager.setPendingSearch(topic, topic);
            return convManager.buildSearchPrompt(topic);
        } catch (Exception e) {
            Log.e(TAG, "generateHumanResponse crash", e);
            return "I'm thinking... (error: " + e.getMessage() + ")";
        }
    }

    private String generateFromNN(String input) {
        return generateFromNNWithContext(input, "");
    }

    private String generateFromNNWithContext(String input, String context) {
        try {
            String narsCtx = nars.buildContextFromBeliefs(convManager.extractTopic(input));
            String fullInput = (context.isEmpty()? "" : context + " ") + (narsCtx.isEmpty()? "" : narsCtx + " ") + input;
            int[] tokens = tokenizer.encode(fullInput);
            StringBuilder sb = new StringBuilder();
            int[] current = tokens;
            for (int i = 0; i < 35; i++) {
                int next = nn.generateNextToken(current, 0.8f);
                if (next == Tokenizer.EOS_TOKEN || next == Tokenizer.PAD_TOKEN) break;
                String word = tokenizer.decodeToken(next);
                if (word.startsWith("<")) break;
                if (sb.length() > 0) sb.append(" ");
                sb.append(word);
                int[] ext = new int[current.length + 1];
                System.arraycopy(current, 0, ext, 0, current.length);
                ext[current.length] = next;
                current = ext;
                if (current.length > NeuralNetwork.MAX_SEQ_LEN) {
                    current = Arrays.copyOfRange(current, current.length - NeuralNetwork.MAX_SEQ_LEN, current.length);
                }
            }
            return sb.toString().trim();
        } catch (Exception e) { return ""; }
    }

    private String handleSearchConfirmation(String input) {
        try {
            String topic = convManager.getPendingTopic();
            String query = convManager.getPendingSearchQuery();
            convManager.clearPending();
            if (convManager.isYes(input)) {
                setStatus("Searching for " + topic + "...");
                List<WebSearch.SearchResult> results = webSearch.search(query);
                if (results.isEmpty()) return "I searched but found nothing for \"" + topic + "\". Try rephrasing?";
                String summary = webSearch.summarizeResults(results);
                List<String> facts = webSearch.extractFacts(results);
                selfLearner.learnFromWebResults(facts);
                for (WebSearch.SearchResult r : results) nars.parseAndLearn(r.snippet);
                return convManager.buildLearnedFromWebResponse(topic, summary);
            } else if (convManager.isNo(input)) {
                return convManager.buildSearchDeclinedResponse(topic);
            } else {
                convManager.setPendingSearch(query, topic);
                return "Should I search for \"" + topic + "\"? (yes/no)";
            }
        } catch (Exception e) {
            return "Search error: " + e.getMessage();
        }
    }

    private String handleWebSearch(String query) {
        try {
            setStatus("Searching: " + query);
            List<WebSearch.SearchResult> results = webSearch.search(query);
            if (results.isEmpty()) return "Nothing found for: " + query;
            String summary = webSearch.summarizeResults(results);
            List<String> facts = webSearch.extractFacts(results);
            selfLearner.learnFromWebResults(facts);
            for (WebSearch.SearchResult r : results) nars.parseAndLearn(r.snippet);
            emotionSystem.onLearnedSomethingNew();
            return "🌐 " + summary + "\n\n(Learned " + facts.size() + " facts!)";
        } catch (Exception e) {
            return "Web search failed: " + e.getMessage();
        }
    }

    private String handleWebFetch(String url) {
        try {
            setStatus("Fetching...");
            String[] res = webFetch.fetch(url);
            tokenizer.learnFromText(res[1]);
            nars.parseAndLearn(res[1]);
            selfLearner.learnFromWebResults(Collections.singletonList(res[1]));
            emotionSystem.onLearnedSomethingNew();
            return "📄 " + res[0] + "\n\n" + res[1].substring(0, Math.min(400, res[1].length())) + "...\n\n(Learned from page!)";
        } catch (Exception e) {
            return "Fetch failed: " + e.getMessage();
        }
    }

    private String handleNameInput(String input) {
        try {
            String name = input.trim();
            if (!name.isEmpty()) name = Character.toUpperCase(name.charAt(0)) + name.substring(1);
            userMemory.setName(name);
            return convManager.buildNameResponse(name);
        } catch (Exception e) {
            return "Nice to meet you!";
        }
    }

    private void copyBundledDatasetIfMissing() {
        try {
            if (weightManager == null || weightManager.getDatasetDir() == null) return;
            File dest = new File(weightManager.getDatasetDir(), "whisper_personality.jsonl");
            if (dest.exists() && dest.length() > 100) return;
            if (dest.getParentFile()!= null &&!dest.getParentFile().exists()) dest.getParentFile().mkdirs();
            // check raw resource exists
            int resId;
            try {
                resId = R.raw.whisper_personality;
            } catch (Exception e) {
                Log.e(TAG, "raw resource not found: " + e.getMessage());
                return;
            }
            InputStream is = getResources().openRawResource(resId);
            FileOutputStream fos = new FileOutputStream(dest);
            byte[] buf = new byte[4096];
            int len;
            while ((len = is.read(buf)) > 0) fos.write(buf, 0, len);
            is.close();
            fos.close();
            Log.d(TAG, "Bundled dataset copied to " + dest.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "Bundled dataset copy failed: " + e.getMessage());
        }
    }

    private String handleListDatasets() {
        try {
            List<String> ds = datasetLoader.listDatasets();
            if (ds.isEmpty()) return "No datasets in:\n" + weightManager.getDatasetPath() + "\n\nDrop.jsonl /.csv /.txt HuggingFace files there!";
            StringBuilder sb = new StringBuilder("Datasets I can learn from:\n");
            for (String d : ds) sb.append("• ").append(d).append("\n");
            sb.append("\nUse!load <filename>");
            return sb.toString();
        } catch (Exception e) {
            return "List datasets error: " + e.getMessage();
        }
    }

    private void handleLoadDataset(String filename) {
        mainHandler.post(() -> {
            try {
                addBotMessage("Loading " + filename + "...");
                if (progressBar!= null) progressBar.setVisibility(View.VISIBLE);
            } catch (Exception e) {}
        });
        datasetLoader.loadAsync(filename, new DatasetLoader.LoadCallback() {
            @Override public void onProgress(int l, int t, String f) {
                mainHandler.post(() -> setStatus("Loaded " + l + " samples..."));
            }
            @Override public void onComplete(List<DatasetLoader.TrainingSample> samples) {
                mainHandler.post(() -> addBotMessage("Got " + samples.size() + " samples! Training... 🧠"));
                selfLearner.learnFromDataset(samples, new SelfLearner.LearningCallback() {
                    @Override public void onProgress(int s, int t, float loss, String status) {
                        mainHandler.post(() -> setStatus(status + " | Loss: " + String.format("%.4f", loss)));
                    }
                    @Override public void onComplete(float avgLoss, int steps) {
                        if (emotionSystem!= null) emotionSystem.onLearnedSomethingNew();
                        mainHandler.post(() -> {
                            if (progressBar!= null) progressBar.setVisibility(View.GONE);
                            addBotMessage("Done! I just got smarter 😄 Steps: " + steps);
                            if (sendButton!= null) sendButton.setEnabled(true);
                            setStatus(getMoodStatus());
                        });
                    }
                    @Override public void onError(String error) {
                        mainHandler.post(() -> {
                            if (progressBar!= null) progressBar.setVisibility(View.GONE);
                            addBotMessage("Training error: " + error);
                            if (sendButton!= null) sendButton.setEnabled(true);
                        });
                    }
                });
            }
            @Override public void onError(String error) {
                mainHandler.post(() -> {
                    if (progressBar!= null) progressBar.setVisibility(View.GONE);
                    addBotMessage("Can't load that: " + error);
                    if (sendButton!= null) sendButton.setEnabled(true);
                });
            }
        });
    }

    private void scheduleProactiveFact() {
        try {
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                try {
                    List<Belief> beliefs = nars.getRelatedBeliefs(userMemory.getFavoriteTopic()!= null? userMemory.getFavoriteTopic() : "");
                    String fact = convManager.buildProactiveFact(beliefs, userMemory.getName());
                    if (fact!= null) addBotMessage(fact);
                } catch (Exception e) {}
            }, 3000);
        } catch (Exception e) {}
    }

    private String handleReset() {
        try {
            weightManager.resetAll();
            nn = new NeuralNetwork(); tokenizer = new Tokenizer(); nars = new NARSEngine();
            weightManager = new WeightManager(MainActivity.this, nn, tokenizer, nars);
            selfLearner = new SelfLearner(nn, tokenizer, nars, weightManager);
            datasetLoader = new DatasetLoader(weightManager.getDatasetDir());
            onnxEngine = new OnnxEngine(MainActivity.this, weightManager);
            personalityEngine = new PersonalityEngine(onnxEngine, tokenizer);
            convManager.clearPending();
            return "Brain wiped. I'm a blank slate again. But I still remember I exist 🧠";
        } catch (Exception e) {
            return "Reset failed: " + e.getMessage();
        }
    }

    private void showMenu() {
        try {
            String botName = birthStory!= null? birthStory.getBotName() : "AIBot";
            String[] opts = {"Load Dataset", "Web Search", "Fetch URL", "Device Status", "Screen Reader Setup", "Overlay Bubble", "Load ONNX Model", "View Stats", "Save Brain", "Reset Brain", "Help"};
            new AlertDialog.Builder(this).setTitle(botName + " " + (emotionSystem!= null? emotionSystem.getMoodEmoji() : "")).setItems(opts, (d, i) -> {
                try {
                    switch (i) {
                        case 0: showDatasetPicker(); break;
                        case 1: showSearchDialog(); break;
                        case 2: showFetchDialog(); break;
                        case 3: addBotMessage(deviceController.getDeviceStatus()); break;
                        case 4: showAccessibilitySetup(); break;
                        case 5: addBotMessage(handleOverlayToggle()); break;
                        case 6: showOnnxPicker(); break;
                        case 7: showStats(); break;
                        case 8: if (weightManager!= null) weightManager.saveAll(); Toast.makeText(this, "Saved!", Toast.LENGTH_SHORT).show(); break;
                        case 9: confirmReset(); break;
                        case 10: addBotMessage(buildHelp()); break;
                    }
                } catch (Exception e) {
                    Log.e(TAG, "menu action crash", e);
                }
            }).show();
        } catch (Exception e) {
            Log.e(TAG, "showMenu crash", e);
        }
    }

    private void showDatasetPicker() {
        try {
            List<String> ds = datasetLoader.listDatasets();
            if (ds.isEmpty()) {
                Toast.makeText(this, "No datasets in " + weightManager.getDatasetPath(), Toast.LENGTH_LONG).show(); return;
            }
            String[] arr = ds.toArray(new String[0]);
            new AlertDialog.Builder(this).setTitle("Pick Dataset").setItems(arr, (d, i) -> handleLoadDataset(arr[i])).show();
        } catch (Exception e) {
            Log.e(TAG, "showDatasetPicker crash", e);
        }
    }

    private void showSearchDialog() {
        try {
            EditText et = new EditText(this); et.setHint("Search query...");
            new AlertDialog.Builder(this).setTitle("Web Search").setView(et).setPositiveButton("Search", (d, w) -> {
                String q = et.getText().toString().trim();
                if (!q.isEmpty()) {
                    addUserMessage("!search " + q);
                    new Thread(() -> { String r = handleWebSearch(q); mainHandler.post(() -> addBotMessage(r)); }).start();
                }
            }).setNegativeButton("Cancel", null).show();
        } catch (Exception e) {}
    }

    private void showFetchDialog() {
        try {
            EditText et = new EditText(this); et.setHint("https://...");
            new AlertDialog.Builder(this).setTitle("Fetch Page").setView(et).setPositiveButton("Fetch", (d, w) -> {
                String url = et.getText().toString().trim();
                if (!url.isEmpty()) {
                    addUserMessage("!fetch " + url);
                    new Thread(() -> { String r = handleWebFetch(url); mainHandler.post(() -> addBotMessage(r)); }).start();
                }
            }).setNegativeButton("Cancel", null).show();
        } catch (Exception e) {}
    }

    private void showAccessibilitySetup() {
        try {
            String status = ScreenReaderService.isRunning()? "✅ Screen reader is ACTIVE" : "❌ Screen reader is NOT enabled";
            new AlertDialog.Builder(this).setTitle("Screen Reader").setMessage(status + "\n\nTo enable:\nSettings → Accessibility → Installed Services → AIBot → Enable\n\nThis lets me read what's on your screen and help with games!").setPositiveButton("Open Settings", (d, w) -> {
                Intent i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS); i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(i);
            }).setNegativeButton("Close", null).show();
        } catch (Exception e) {}
    }

    private void showOnnxPicker() {
        try {
            List<String> models = onnxEngine.listAvailableModels();
            if (models.isEmpty()) {
                new AlertDialog.Builder(this).setTitle("ONNX Models").setMessage("No.onnx models found in:\n" + onnxEngine.getModelPath() + "\n\nTo create one:\n1. Train the bot\n2. Pull model.bin from device\n3. Run export_to_onnx.py on PC\n4. Copy.onnx back to models/ folder\n\nOr for personality model:\npython export_to_onnx.py --personality whisper_personality.jsonl").setPositiveButton("OK", null).show();
                return;
            }
            String[] arr = models.toArray(new String[0]);
            new AlertDialog.Builder(this).setTitle("Load ONNX Model").setItems(arr, (d, i) -> {
                boolean loaded = personalityEngine.loadModel(arr[i]);
                addBotMessage(loaded? "ONNX model loaded: " + arr[i] + "\nPersonality engine is now active." : "Failed to load " + arr[i] + ". Check format.");
            }).show();
        } catch (Exception e) {}
    }

    private void showStats() {
        try {
            String botName = birthStory!= null? birthStory.getBotName() : "AIBot";
            new AlertDialog.Builder(this).setTitle(botName + " Stats " + (emotionSystem!= null? emotionSystem.getMoodEmoji() : "")).setMessage(
                "Age: " + (birthStory!= null? birthStory.getAgeString() : "unknown") + "\n" +
                "Device: " + Build.MODEL + "\n\n" +
                (weightManager!= null? weightManager.getInfo() : "no info") + "\n" +
                "Sessions: " + (userMemory!= null? userMemory.getSessionCount() : 0) + "\n" +
                "Messages: " + (userMemory!= null? userMemory.getTotalMessages() : 0) + "\n" +
                "Train steps: " + (selfLearner!= null? selfLearner.getTrainSteps() : 0) + "\n" +
                "Mood: " + (emotionSystem!= null? emotionSystem.getMood() : "unknown") + " " + (emotionSystem!= null? emotionSystem.getMoodEmoji() : "") + "\n\n" +
                (nars!= null? nars.getStats() : "") + "\n\n" +
                "Screen reader: " + (ScreenReaderService.isRunning()? "Active ✅" : "Inactive ❌") + "\n" +
                "ONNX: " + (personalityEngine!= null? personalityEngine.getStatus() : "unknown") + "\n" +
                "Overlay: " + (overlayManager!= null && overlayManager.isShowing()? "Showing ✅" : "Hidden") + "\n\n" +
                (weightManager!= null? weightManager.getStorageInfo() : "")
            ).setPositiveButton("OK", null).show();
        } catch (Exception e) {}
    }

    private void confirmReset() {
        new AlertDialog.Builder(this).setTitle("Reset Brain?").setMessage("I'll forget everything I learned. Are you sure?").setPositiveButton("Reset", (d, w) -> addBotMessage(handleReset())).setNegativeButton("Cancel", null).show();
    }

    private String buildStats() {
        try {
            return (weightManager!= null? weightManager.getInfo() : "") + "\n" +
                   "Sessions: " + (userMemory!= null? userMemory.getSessionCount() : 0) + "\n" +
                   "Mood: " + (emotionSystem!= null? emotionSystem.getMood() : "") + " " + (emotionSystem!= null? emotionSystem.getMoodEmoji() : "") + "\n" +
                   (nars!= null? nars.getStats() : "") + "\n" +
                   "Screen: " + (ScreenReaderService.isRunning()? "Active" : "Inactive");
        } catch (Exception e) { return "Stats error"; }
    }

    private String buildHelp() {
        String botName = birthStory!= null? birthStory.getBotName() : "AIBot";
        return "I am " + botName + ". Here's what I can do:\n\n" +
               "💬 CHAT\nJust talk to me — I learn and reply\n\n" +
               "📱 DEVICE CONTROL\n\"Turn on flashlight\"\n\"WiFi off\"\n\"Bluetooth on\"\n\"Open YouTube\"\n\"Battery status\"\n\"Device status\"\n\n" +
               "👁️ SCREEN\n\"What's on screen?\"\n\"What app is open?\"\n\"Help me with this game\"\n\n" +
               "🌐 WEB\n!search <query>\n!fetch <url>\n\n" +
               "📁 DATASETS\n!datasets — list files\n!load <file> — train\n\n" +
               "⚙️ SYSTEM\n!stats!save!reset\n\"show bubble\" — floating overlay\n\n" +
               "Enable screen reader in:\nSettings → Accessibility → AIBot";
    }

    private String getMoodStatus() {
        try {
            String name = birthStory!= null? birthStory.getBotName() : "AIBot";
            String emoji = emotionSystem!= null? emotionSystem.getMoodEmoji() : "";
            String info = weightManager!= null? weightManager.getInfo() : "";
            return name + " " + emoji + " | " + info;
        } catch (Exception e) { return "AIBot"; }
    }

    private void addUserMessage(String text) {
        try {
            if (chatRecycler == null || chatAdapter == null) return;
            mainHandler.post(() -> {
                try {
                    messages.add(new ChatMessage(text, true));
                    chatAdapter.notifyItemInserted(messages.size() - 1);
                    chatRecycler.scrollToPosition(messages.size() - 1);
                } catch (Exception e) { Log.e(TAG, "addUserMessage crash", e); }
            });
        } catch (Exception e) {}
    }

    private void addBotMessage(String text) {
        try {
            if (chatRecycler == null || chatAdapter == null) {
                // queue until UI ready
                messages.add(new ChatMessage(text, false));
                return;
            }
            mainHandler.post(() -> {
                try {
                    messages.add(new ChatMessage(text, false));
                    chatAdapter.notifyItemInserted(messages.size() - 1);
                    chatRecycler.scrollToPosition(messages.size() - 1);
                } catch (Exception e) { Log.e(TAG, "addBotMessage crash", e); }
            });
        } catch (Exception e) {}
    }

    private void setStatus(String s) {
        try {
            mainHandler.post(() -> {
                try {
                    if (statusText!= null) statusText.setText(s);
                } catch (Exception e) {}
            });
        } catch (Exception e) {}
    }

    private void requestAllPermissions() {
        try {
            List<String> needed = new ArrayList<>();
            needed.add(Manifest.permission.CAMERA);
            needed.add(Manifest.permission.INTERNET);
            needed.add(Manifest.permission.ACCESS_WIFI_STATE);
            needed.add(Manifest.permission.CHANGE_WIFI_STATE);
            needed.add(Manifest.permission.BLUETOOTH);
            needed.add(Manifest.permission.BLUETOOTH_ADMIN);
            needed.add(Manifest.permission.ACCESS_NETWORK_STATE);
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
                needed.add(Manifest.permission.READ_EXTERNAL_STORAGE);
                needed.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                needed.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
            List<String> toRequest = new ArrayList<>();
            for (String p : needed) {
                if (ContextCompat.checkSelfPermission(this, p)!= PackageManager.PERMISSION_GRANTED)
                    toRequest.add(p);
            }
            if (!toRequest.isEmpty())
                ActivityCompat.requestPermissions(this, toRequest.toArray(new String[0]), 100);
        } catch (Exception e) {
            Log.e(TAG, "requestAllPermissions crash", e);
        }
    }

    private void checkSpecialPermissions() {
        try {
            if (!OverlayManager.hasOverlayPermission(this)) {
                new Handler(Looper.getMainLooper()).postDelayed(() ->
                    addBotMessage("Tip: Grant me 'Display over other apps' permission so I can float on your screen while you use other apps! Menu → Overlay Bubble"), 3000);
            }
        } catch (Exception e) {}
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            if (weightManager!= null) weightManager.saveAll();
        } catch (Exception e) {}
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            if (overlayManager!= null) overlayManager.hideBubble();
        } catch (Exception e) {}
    }
}
