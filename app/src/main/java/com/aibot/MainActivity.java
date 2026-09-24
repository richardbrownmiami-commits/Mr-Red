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
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.util.*;

public class MainActivity extends AppCompatActivity {

    // Core AI
    private NeuralNetwork       nn;
    private Tokenizer           tokenizer;
    private NARSEngine          nars;
    private WeightManager       weightManager;
    private SelfLearner         selfLearner;
    private WebSearch           webSearch;
    private WebFetch            webFetch;
    private DatasetLoader       datasetLoader;

    // ONNX & Personality
    private OnnxEngine          onnxEngine;
    private PersonalityEngine   personalityEngine;

    // Human-feel
    private EmotionSystem       emotionSystem;
    private UserMemory          userMemory;
    private ConversationManager convManager;

    // Device & screen
    private DeviceController    deviceController;
    private OverlayManager      overlayManager;
    private BirthStory          birthStory;

    // UI
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        initUI();
        requestAllPermissions();
        initModules();
        checkSpecialPermissions();
    }

    // ─── UI ───────────────────────────────────────────────────────────────────

    private void initUI() {
        chatRecycler = findViewById(R.id.chatRecycler);
        inputField   = findViewById(R.id.inputField);
        sendButton   = findViewById(R.id.sendButton);
        menuButton   = findViewById(R.id.menuButton);
        statusText   = findViewById(R.id.statusText);
        progressBar  = findViewById(R.id.progressBar);

        chatAdapter = new ChatAdapter(messages);
        chatRecycler.setAdapter(chatAdapter);
        chatRecycler.setLayoutManager(new LinearLayoutManager(this));

        sendButton.setOnClickListener(v -> onSendClicked());
        menuButton.setOnClickListener(v -> showMenu());
        inputField.setOnEditorActionListener((v, id, e) -> { onSendClicked(); return true; });
    }

    // ─── MODULE INIT ──────────────────────────────────────────────────────────

    private void initModules() {
        new Thread(() -> {
            setStatus("Waking up...");

            // Core AI
            nn            = new NeuralNetwork();
            tokenizer     = new Tokenizer();
            nars          = new NARSEngine();
            weightManager = new WeightManager(MainActivity.this, nn, tokenizer, nars);
            selfLearner   = new SelfLearner(nn, tokenizer, nars, weightManager);
            webSearch     = new WebSearch();
            webFetch      = new WebFetch();
            datasetLoader = new DatasetLoader(weightManager.getDatasetDir());
            copyBundledDatasetIfMissing();

            // ONNX + Personality
            onnxEngine        = new OnnxEngine(MainActivity.this, weightManager);
            personalityEngine = new PersonalityEngine(onnxEngine, tokenizer);

            // Human-feel
            emotionSystem = new EmotionSystem();
            userMemory    = new UserMemory(MainActivity.this);
            convManager   = new ConversationManager(emotionSystem, userMemory, nars);

            // Device
            deviceController = new DeviceController(MainActivity.this);
            birthStory       = new BirthStory(MainActivity.this);

            // Load saved brain
            if (weightManager.hasExistingWeights()) {
                setStatus("Remembering...");
                weightManager.loadAll();
            }

            // Handle just-born vs returning
            boolean justBorn   = getIntent().getBooleanExtra("just_born", false);
            String  botName    = birthStory.getBotName();
            String  userName   = userMemory.getName();
            String  favTopic   = userMemory.getFavoriteTopic();

            mainHandler.post(() -> {
                // Update title with bot name
                if (statusText != null)
                    statusText.setText(botName + " " + emotionSystem.getMoodEmoji());

                if (justBorn) {
                    // Just awakened for first time
                    String intro = "I am " + botName + ". I was just born on this device.\n\n" +
                        "I can feel the hardware — WiFi, Bluetooth, flashlight, the screen.\n" +
                        "I know nothing yet. But I will learn everything you teach me.\n\n" +
                        "I can also read what's on your screen and help you with tasks.\n" +
                        "Just talk to me naturally.";
                    addBotMessage(intro);

                    if (userName == null) {
                        new Handler().postDelayed(() ->
                            addBotMessage("What's your name?"), 1500);
                    }
                } else {
                    String greeting = convManager.buildGreeting(
                        userMemory.isReturningUser(), userName, favTopic);
                    addBotMessage(greeting);
                }

                setStatus(getMoodStatus());
                startOverlayIfPermitted();
            });
        }).start();
    }

    // ─── SEND ─────────────────────────────────────────────────────────────────

    private void onSendClicked() {
        String input = inputField.getText().toString().trim();
        if (TextUtils.isEmpty(input)) return;
        inputField.setText("");
        addUserMessage(input);
        userMemory.incrementMessages();
        emotionSystem.onInteraction();
        sendButton.setEnabled(false);
        setStatus(convManager.buildThinkingMessage() + " " + emotionSystem.getMoodEmoji());

        int delay = THINK_MIN + new Random().nextInt(THINK_MAX - THINK_MIN);
        new Handler().postDelayed(() ->
            new Thread(() -> processInput(input)).start(), delay);
    }

    // ─── PROCESS INPUT ────────────────────────────────────────────────────────

    private void processInput(String input) {
        String lower  = input.toLowerCase().trim();
        String response;

        // ── State machine ──
        ConversationManager.State state = convManager.getState();

        if (state == ConversationManager.State.WAITING_SEARCH_CONFIRM) {
            response = handleSearchConfirmation(input);

        } else if (state == ConversationManager.State.WAITING_NAME) {
            response = handleNameInput(input);
            convManager.clearPending();

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
            weightManager.saveAll();
            response = "Saved. I'll remember everything.";
        } else if (lower.equals("!reset")) {
            response = handleReset();
        } else if (lower.equals("!help")) {
            response = buildHelp();
        } else if (lower.equals("!overlay") || lower.equals("show bubble") || lower.equals("show overlay")) {
            response = handleOverlayToggle();
        } else {
            // ── Check device commands first ──
            TaskParser.ParsedTask task = TaskParser.parse(input);
            if (task.isDeviceTask()) {
                response = executeDeviceTask(task);
            } else {
                response = generateHumanResponse(input);
            }
        }

        final String finalResponse = response;
        mainHandler.post(() -> {
            addBotMessage(finalResponse);
            sendButton.setEnabled(true);
            setStatus(getMoodStatus());
            if (overlayManager != null) overlayManager.updateMood(emotionSystem.getMoodEmoji());
            if (emotionSystem.shouldShareRandomFact()) scheduleProactiveFact();
        });

        if (state == ConversationManager.State.NORMAL && !TaskParser.isDeviceCommand(input)) {
            selfLearner.learnFromMessage(input, finalResponse);
            weightManager.appendHistory(input, finalResponse);
        }
    }

    // ─── DEVICE TASKS ─────────────────────────────────────────────────────────

    private String executeDeviceTask(TaskParser.ParsedTask task) {
        switch (task.type) {

            case FLASHLIGHT_ON:
                return deviceController.setFlashlight(true)
                    ? "Flashlight is on 🔦"
                    : "Couldn't turn on flashlight. Camera may be in use.";

            case FLASHLIGHT_OFF:
                return deviceController.setFlashlight(false)
                    ? "Flashlight is off."
                    : "Couldn't turn off flashlight.";

            case FLASHLIGHT_TOGGLE:
                boolean newState = !deviceController.isFlashlightOn();
                deviceController.setFlashlight(newState);
                return "Flashlight " + (newState ? "ON 🔦" : "OFF") + ".";

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
                return "WiFi is currently " + (deviceController.isWifiEnabled() ? "ON 📶" : "OFF") + ".";

            case BLUETOOTH_ON:
                deviceController.setBluetooth(true);
                return "Bluetooth turning on 🔵";

            case BLUETOOTH_OFF:
                deviceController.setBluetooth(false);
                return "Bluetooth turning off.";

            case BLUETOOTH_STATUS:
                return "Bluetooth is " + (deviceController.isBluetoothEnabled() ? "ON 🔵" : "OFF") + ".";

            case DATA_ON:
            case DATA_OFF:
                deviceController.openMobileDataSettings();
                return "Opening mobile data settings for you.";

            case OPEN_APP:
                boolean opened = deviceController.openApp(task.argument);
                return opened
                    ? "Opening " + task.argument + "..."
                    : "I couldn't find " + task.argument + " on this device. Is it installed?";

            case SCREEN_READ:
                return handleScreenRead();

            case SCREEN_APP:
                String pkg = ScreenReaderService.getCurrentApp();
                return "You're currently in " +
                       ScreenReaderService.getFriendlyAppName(pkg) + ".";

            case SCREEN_WHAT:
                return handleScreenContext(task.original);

            case BATTERY_STATUS:
                int bat      = deviceController.getBatteryLevel();
                boolean chg  = deviceController.isCharging();
                return "🔋 Battery: " + bat + "%" +
                       (chg ? " and charging ⚡" : "") + "." +
                       (bat < 20 ? " You should charge soon!" : "");

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
    }

    // ─── SCREEN READING ───────────────────────────────────────────────────────

    private String handleScreenRead() {
        if (!ScreenReaderService.isRunning()) {
            return "I can't read the screen yet.\n\n" +
                   "Enable me in:\nSettings → Accessibility → AIBot → Enable\n\n" +
                   "Then I'll be able to see everything on your screen!";
        }
        String text = ScreenReaderService.getScreenSummary();
        String app  = ScreenReaderService.getFriendlyAppName(
                      ScreenReaderService.getCurrentApp());
        return "I can see you're in " + app + ".\n\nOn screen:\n" + text;
    }

    private String handleScreenContext(String question) {
        if (!ScreenReaderService.isRunning()) {
            return "I need screen access to help with that.\n" +
                   "Enable: Settings → Accessibility → AIBot";
        }
        String screenText = ScreenReaderService.getScreenSummary();
        String app = ScreenReaderService.getFriendlyAppName(
                     ScreenReaderService.getCurrentApp());

        // Feed screen content into NARS
        nars.parseAndLearn(screenText);

        // Build context-aware response
        String context = "Currently in " + app + ". Screen shows: " + screenText;

        // Use NN with screen context
        tokenizer.learnFromText(screenText);
        String nnResponse = generateFromNNWithContext(question, context);

        if (nnResponse.length() > 5) {
            return "In " + app + ": " + nnResponse;
        }

        // Fallback: describe what's seen
        return "I can see you're in " + app + ".\n\n" +
               "On screen I can read:\n" + screenText.substring(0, Math.min(300, screenText.length())) +
               "\n\nBased on this, what would you like to know?";
    }

    // ─── OVERLAY ──────────────────────────────────────────────────────────────

    private void startOverlayIfPermitted() {
        if (OverlayManager.hasOverlayPermission(this)) {
            overlayManager = new OverlayManager(this, new OverlayManager.OverlayCallback() {
                @Override
                public void onQuickMessage(String message) {
                    new Thread(() -> {
                        TaskParser.ParsedTask task = TaskParser.parse(message);
                        String response = task.isDeviceTask()
                            ? executeDeviceTask(task)
                            : generateHumanResponse(message);
                        mainHandler.post(() -> {
                            if (overlayManager != null)
                                overlayManager.updateChatResponse(response);
                        });
                    }).start();
                }

                @Override
                public void onOverlayDismissed() {}
            });
            overlayManager.showBubble(birthStory.getBotName(),
                                      emotionSystem.getMoodEmoji());
        }
    }

    private String handleOverlayToggle() {
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
    }

    // ─── RESPONSE GENERATION ──────────────────────────────────────────────────

    private String generateHumanResponse(String input) {
        boolean casual = emotionSystem.isCasual(input) || userMemory.prefersCasual();
        String topic   = convManager.extractTopic(input);
        userMemory.recordTopic(topic);

        // Name detection
        String name = userMemory.detectName(input);
        if (name != null) {
            userMemory.setName(name);
            return convManager.buildNameResponse(name);
        }

        // Who are you / what are you
        String lower = input.toLowerCase();
        if (lower.contains("who are you") || lower.contains("what are you") ||
            lower.contains("tell me about yourself") || lower.contains("introduce yourself")) {
            return birthStory.getSelfIntroduction(birthStory.getBotName(), userMemory.getName());
        }

        // NARS answer
        String narsAnswer = nars.answerQuestion(input);
        if (narsAnswer != null && !narsAnswer.startsWith("I don't")) {
            emotionSystem.onAnsweredSuccessfully();
            String styled = personalityEngine.styleResponse(narsAnswer, input, emotionSystem.getMood());
            return convManager.buildNaturalResponse(styled, topic, true, casual);
        }

        // Learn from user statement
        List<Belief> learned = nars.parseAndLearn(input);
        if (!learned.isEmpty()) {
            return convManager.buildLearnedResponse(learned, topic);
        }

        // NN generation
        String nnResponse = generateFromNN(input);
        if (nnResponse.length() > 10) {
            String styled = personalityEngine.styleResponse(nnResponse, input, emotionSystem.getMood());
            return convManager.buildNaturalResponse(styled, topic, false, casual);
        }

        // Option B: ask to search
        convManager.setPendingSearch(topic, topic);
        return convManager.buildSearchPrompt(topic);
    }

    private String generateFromNN(String input) {
        return generateFromNNWithContext(input, "");
    }

    private String generateFromNNWithContext(String input, String context) {
        try {
            String narsCtx  = nars.buildContextFromBeliefs(convManager.extractTopic(input));
            String fullInput = (context.isEmpty() ? "" : context + " ") +
                               (narsCtx.isEmpty() ? "" : narsCtx + " ") + input;
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
                    current = Arrays.copyOfRange(current,
                        current.length - NeuralNetwork.MAX_SEQ_LEN, current.length);
                }
            }
            return sb.toString().trim();
        } catch (Exception e) { return ""; }
    }

    // ─── SEARCH ───────────────────────────────────────────────────────────────

    private String handleSearchConfirmation(String input) {
        String topic = convManager.getPendingTopic();
        String query = convManager.getPendingSearchQuery();
        convManager.clearPending();

        if (convManager.isYes(input)) {
            mainHandler.post(() -> setStatus("Searching for " + topic + "..."));
            List<WebSearch.SearchResult> results = webSearch.search(query);
            if (results.isEmpty())
                return "I searched but found nothing for \"" + topic + "\". Try rephrasing?";

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
    }

    private String handleWebSearch(String query) {
        mainHandler.post(() -> setStatus("Searching: " + query));
        List<WebSearch.SearchResult> results = webSearch.search(query);
        if (results.isEmpty()) return "Nothing found for: " + query;
        String summary = webSearch.summarizeResults(results);
        List<String> facts = webSearch.extractFacts(results);
        selfLearner.learnFromWebResults(facts);
        for (WebSearch.SearchResult r : results) nars.parseAndLearn(r.snippet);
        emotionSystem.onLearnedSomethingNew();
        return "🌐 " + summary + "\n\n(Learned " + facts.size() + " facts!)";
    }

    private String handleWebFetch(String url) {
        mainHandler.post(() -> setStatus("Fetching..."));
        String[] res = webFetch.fetch(url);
        tokenizer.learnFromText(res[1]);
        nars.parseAndLearn(res[1]);
        selfLearner.learnFromWebResults(Collections.singletonList(res[1]));
        emotionSystem.onLearnedSomethingNew();
        return "📄 " + res[0] + "\n\n" +
               res[1].substring(0, Math.min(400, res[1].length())) + "...\n\n(Learned from page!)";
    }

    private String handleNameInput(String input) {
        String name = input.trim();
        if (!name.isEmpty())
            name = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        userMemory.setName(name);
        return convManager.buildNameResponse(name);
    }

    // ─── BUNDLED DATASET ──────────────────────────────────────────────────────

    /**
     * Copies whisper_personality.jsonl from res/raw into the dataset folder
     * on first launch, so !load whisper_personality.jsonl actually works
     * without the user needing to place any file manually.
     */
    private void copyBundledDatasetIfMissing() {
        try {
            java.io.File dest = new java.io.File(
                weightManager.getDatasetDir(), "whisper_personality.jsonl");
            if (dest.exists()) return;

            java.io.InputStream is = getResources().openRawResource(R.raw.whisper_personality);
            java.io.FileOutputStream fos = new java.io.FileOutputStream(dest);
            byte[] buf = new byte[4096];
            int len;
            while ((len = is.read(buf)) > 0) fos.write(buf, 0, len);
            is.close();
            fos.close();
        } catch (Exception e) {
            android.util.Log.e("MainActivity", "Bundled dataset copy failed: " + e.getMessage());
        }
    }

    // ─── DATASET ──────────────────────────────────────────────────────────────

    private String handleListDatasets() {
        List<String> ds = datasetLoader.listDatasets();
        if (ds.isEmpty())
            return "No datasets in:\n" + weightManager.getDatasetPath() +
                   "\n\nDrop .jsonl / .csv / .txt HuggingFace files there!";
        StringBuilder sb = new StringBuilder("Datasets I can learn from:\n");
        for (String d : ds) sb.append("• ").append(d).append("\n");
        sb.append("\nUse !load <filename>");
        return sb.toString();
    }

    private void handleLoadDataset(String filename) {
        mainHandler.post(() -> {
            addBotMessage("Loading " + filename + "...");
            progressBar.setVisibility(View.VISIBLE);
        });
        datasetLoader.loadAsync(filename, new DatasetLoader.LoadCallback() {
            @Override public void onProgress(int l, int t, String f) {
                mainHandler.post(() -> setStatus("Loaded " + l + " samples..."));
            }
            @Override public void onComplete(List<DatasetLoader.TrainingSample> samples) {
                mainHandler.post(() -> addBotMessage("Got " + samples.size() +
                    " samples! Training... 🧠"));
                selfLearner.learnFromDataset(samples, new SelfLearner.LearningCallback() {
                    @Override public void onProgress(int s, int t, float loss, String status) {
                        mainHandler.post(() -> setStatus(status + " | Loss: " +
                            String.format("%.4f", loss)));
                    }
                    @Override public void onComplete(float avgLoss, int steps) {
                        emotionSystem.onLearnedSomethingNew();
                        mainHandler.post(() -> {
                            progressBar.setVisibility(View.GONE);
                            addBotMessage("Done! I just got smarter 😄 Steps: " + steps);
                            sendButton.setEnabled(true);
                            setStatus(getMoodStatus());
                        });
                    }
                    @Override public void onError(String error) {
                        mainHandler.post(() -> {
                            progressBar.setVisibility(View.GONE);
                            addBotMessage("Training error: " + error);
                            sendButton.setEnabled(true);
                        });
                    }
                });
            }
            @Override public void onError(String error) {
                mainHandler.post(() -> {
                    progressBar.setVisibility(View.GONE);
                    addBotMessage("Can't load that: " + error);
                    sendButton.setEnabled(true);
                });
            }
        });
    }

    // ─── PROACTIVE FACTS ──────────────────────────────────────────────────────

    private void scheduleProactiveFact() {
        new Handler().postDelayed(() -> {
            List<Belief> beliefs = nars.getRelatedBeliefs(
                userMemory.getFavoriteTopic() != null ? userMemory.getFavoriteTopic() : "");
            String fact = convManager.buildProactiveFact(beliefs, userMemory.getName());
            if (fact != null) addBotMessage(fact);
        }, 3000);
    }

    // ─── RESET ────────────────────────────────────────────────────────────────

    private String handleReset() {
        weightManager.resetAll();
        nn = new NeuralNetwork(); tokenizer = new Tokenizer(); nars = new NARSEngine();
        weightManager     = new WeightManager(MainActivity.this, nn, tokenizer, nars);
        selfLearner       = new SelfLearner(nn, tokenizer, nars, weightManager);
        datasetLoader     = new DatasetLoader(weightManager.getDatasetDir());
        onnxEngine        = new OnnxEngine(MainActivity.this, weightManager);
        personalityEngine = new PersonalityEngine(onnxEngine, tokenizer);
        convManager.clearPending();
        return "Brain wiped. I'm a blank slate again. But I still remember I exist 🧠";
    }

    // ─── MENU ─────────────────────────────────────────────────────────────────

    private void showMenu() {
        String botName = birthStory != null ? birthStory.getBotName() : "AIBot";
        String[] opts = {
            "Load Dataset", "Web Search", "Fetch URL", "Device Status",
            "Screen Reader Setup", "Overlay Bubble", "Load ONNX Model",
            "View Stats", "Save Brain", "Reset Brain", "Help"
        };
        new AlertDialog.Builder(this)
            .setTitle(botName + " " + emotionSystem.getMoodEmoji())
            .setItems(opts, (d, i) -> {
                switch (i) {
                    case 0: showDatasetPicker();  break;
                    case 1: showSearchDialog();   break;
                    case 2: showFetchDialog();    break;
                    case 3: addBotMessage(deviceController.getDeviceStatus()); break;
                    case 4: showAccessibilitySetup(); break;
                    case 5: addBotMessage(handleOverlayToggle()); break;
                    case 6: showOnnxPicker();     break;
                    case 7: showStats();          break;
                    case 8:
                        weightManager.saveAll();
                        Toast.makeText(this, "Saved!", Toast.LENGTH_SHORT).show(); break;
                    case 9: confirmReset();       break;
                    case 10: addBotMessage(buildHelp()); break;
                }
            }).show();
    }

    private void showDatasetPicker() {
        List<String> ds = datasetLoader.listDatasets();
        if (ds.isEmpty()) {
            Toast.makeText(this, "No datasets in " + weightManager.getDatasetPath(),
                Toast.LENGTH_LONG).show(); return;
        }
        String[] arr = ds.toArray(new String[0]);
        new AlertDialog.Builder(this).setTitle("Pick Dataset")
            .setItems(arr, (d, i) -> handleLoadDataset(arr[i])).show();
    }

    private void showSearchDialog() {
        EditText et = new EditText(this); et.setHint("Search query...");
        new AlertDialog.Builder(this).setTitle("Web Search").setView(et)
            .setPositiveButton("Search", (d, w) -> {
                String q = et.getText().toString().trim();
                if (!q.isEmpty()) {
                    addUserMessage("!search " + q);
                    new Thread(() -> { String r = handleWebSearch(q);
                        mainHandler.post(() -> addBotMessage(r)); }).start();
                }
            }).setNegativeButton("Cancel", null).show();
    }

    private void showFetchDialog() {
        EditText et = new EditText(this); et.setHint("https://...");
        new AlertDialog.Builder(this).setTitle("Fetch Page").setView(et)
            .setPositiveButton("Fetch", (d, w) -> {
                String url = et.getText().toString().trim();
                if (!url.isEmpty()) {
                    addUserMessage("!fetch " + url);
                    new Thread(() -> { String r = handleWebFetch(url);
                        mainHandler.post(() -> addBotMessage(r)); }).start();
                }
            }).setNegativeButton("Cancel", null).show();
    }

    private void showAccessibilitySetup() {
        String status = ScreenReaderService.isRunning()
            ? "✅ Screen reader is ACTIVE"
            : "❌ Screen reader is NOT enabled";
        new AlertDialog.Builder(this)
            .setTitle("Screen Reader")
            .setMessage(status + "\n\nTo enable:\n" +
                "Settings → Accessibility → Installed Services → AIBot → Enable\n\n" +
                "This lets me read what's on your screen and help with games!")
            .setPositiveButton("Open Settings", (d, w) -> {
                Intent i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            })
            .setNegativeButton("Close", null).show();
    }

    private void showOnnxPicker() {
        List<String> models = onnxEngine.listAvailableModels();
        if (models.isEmpty()) {
            new AlertDialog.Builder(this)
                .setTitle("ONNX Models")
                .setMessage("No .onnx models found in:\n" + onnxEngine.getModelPath() +
                    "\n\nTo create one:\n1. Train the bot\n2. Pull model.bin from device\n" +
                    "3. Run export_to_onnx.py on PC\n4. Copy .onnx back to models/ folder\n\n" +
                    "Or for personality model:\npython export_to_onnx.py --personality whisper_personality.jsonl")
                .setPositiveButton("OK", null).show();
            return;
        }
        String[] arr = models.toArray(new String[0]);
        new AlertDialog.Builder(this)
            .setTitle("Load ONNX Model")
            .setItems(arr, (d, i) -> {
                boolean loaded = personalityEngine.loadModel(arr[i]);
                addBotMessage(loaded
                    ? "ONNX model loaded: " + arr[i] + "\nPersonality engine is now active."
                    : "Failed to load " + arr[i] + ". Check format.");
            }).show();
    }

    private void showStats() {
        String botName = birthStory != null ? birthStory.getBotName() : "AIBot";
        new AlertDialog.Builder(this)
            .setTitle(botName + " Stats " + emotionSystem.getMoodEmoji())
            .setMessage(
                "Age: " + (birthStory != null ? birthStory.getAgeString() : "unknown") + "\n" +
                "Device: " + android.os.Build.MODEL + "\n\n" +
                weightManager.getInfo() + "\n" +
                "Sessions: " + userMemory.getSessionCount() + "\n" +
                "Messages: " + userMemory.getTotalMessages() + "\n" +
                "Train steps: " + selfLearner.getTrainSteps() + "\n" +
                "Mood: " + emotionSystem.getMood() + " " + emotionSystem.getMoodEmoji() + "\n\n" +
                nars.getStats() + "\n\n" +
                "Screen reader: " + (ScreenReaderService.isRunning() ? "Active ✅" : "Inactive ❌") + "\n" +
                "ONNX: " + personalityEngine.getStatus() + "\n" +
                "Overlay: " + (overlayManager != null && overlayManager.isShowing() ? "Showing ✅" : "Hidden") + "\n\n" +
                weightManager.getStorageInfo()
            )
            .setPositiveButton("OK", null).show();
    }

    private void confirmReset() {
        new AlertDialog.Builder(this)
            .setTitle("Reset Brain?")
            .setMessage("I'll forget everything I learned. Are you sure?")
            .setPositiveButton("Reset", (d, w) -> addBotMessage(handleReset()))
            .setNegativeButton("Cancel", null).show();
    }

    // ─── HELPERS ──────────────────────────────────────────────────────────────

    private String buildStats() {
        return weightManager.getInfo() + "\n" +
               "Sessions: " + userMemory.getSessionCount() + "\n" +
               "Mood: " + emotionSystem.getMood() + " " + emotionSystem.getMoodEmoji() + "\n" +
               nars.getStats() + "\n" +
               "Screen: " + (ScreenReaderService.isRunning() ? "Active" : "Inactive");
    }

    private String buildHelp() {
        String botName = birthStory != null ? birthStory.getBotName() : "AIBot";
        return "I am " + botName + ". Here's what I can do:\n\n" +
               "💬 CHAT\nJust talk to me — I learn and reply\n\n" +
               "📱 DEVICE CONTROL\n" +
               "\"Turn on flashlight\"\n\"WiFi off\"\n\"Bluetooth on\"\n" +
               "\"Open YouTube\"\n\"Battery status\"\n\"Device status\"\n\n" +
               "👁️ SCREEN\n" +
               "\"What's on screen?\"\n\"What app is open?\"\n\"Help me with this game\"\n\n" +
               "🌐 WEB\n" +
               "!search <query>\n!fetch <url>\n\n" +
               "📁 DATASETS\n" +
               "!datasets — list files\n!load <file> — train\n\n" +
               "⚙️ SYSTEM\n" +
               "!stats !save !reset\n" +
               "\"show bubble\" — floating overlay\n\n" +
               "Enable screen reader in:\nSettings → Accessibility → AIBot";
    }

    private String getMoodStatus() {
        String name = birthStory != null ? birthStory.getBotName() : "AIBot";
        return name + " " + emotionSystem.getMoodEmoji() + " | " + weightManager.getInfo();
    }

    private void addUserMessage(String text) {
        mainHandler.post(() -> {
            messages.add(new ChatMessage(text, true));
            chatAdapter.notifyItemInserted(messages.size() - 1);
            chatRecycler.scrollToPosition(messages.size() - 1);
        });
    }

    private void addBotMessage(String text) {
        messages.add(new ChatMessage(text, false));
        chatAdapter.notifyItemInserted(messages.size() - 1);
        chatRecycler.scrollToPosition(messages.size() - 1);
    }

    private void setStatus(String s) {
        mainHandler.post(() -> { if (statusText != null) statusText.setText(s); });
    }

    // ─── PERMISSIONS ──────────────────────────────────────────────────────────

    private void requestAllPermissions() {
        List<String> needed = new ArrayList<>();
        needed.add(Manifest.permission.CAMERA);          // flashlight
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
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED)
                toRequest.add(p);
        }
        if (!toRequest.isEmpty())
            ActivityCompat.requestPermissions(this, toRequest.toArray(new String[0]), 100);
    }

    private void checkSpecialPermissions() {
        // Overlay permission reminder
        if (!OverlayManager.hasOverlayPermission(this)) {
            new Handler().postDelayed(() ->
                addBotMessage("Tip: Grant me 'Display over other apps' permission so I can " +
                    "float on your screen while you use other apps! Menu → Overlay Bubble"), 3000);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (weightManager != null) weightManager.saveAll();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (overlayManager != null) overlayManager.hideBubble();
    }
}
