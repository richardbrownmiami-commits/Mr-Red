package com.aibot;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

public class MainActivity extends AppCompatActivity {

    private NeuralNetwork nn;
    private Tokenizer tokenizer;
    private NARSEngine nars;
    private NarsTool narsTool;
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
    private CognitiveMemory cognitiveMemory;
    private AtomSpaceLite atomSpace;
    private OpenCogBridge openCog;
    private OnaEngine onaEngine;
    private CognitiveContext cognitiveContext;
    private ToolRouter toolRouter;
    private HuggingFaceHub huggingFaceHub;
    private final StringBuilder neuralTrace = new StringBuilder();
    private static final int TRACE_MAX = 12000;

    private RecyclerView chatRecycler;
    private ChatAdapter chatAdapter;
    private EditText inputField;
    private ImageButton sendButton;
    private ImageButton menuButton;
    private TextView statusText;
    private ProgressBar progressBar;

    private List<ChatMessage> messages = new ArrayList<>();
    private Handler mainHandler = new Handler(Looper.getMainLooper());

    private static final int THINK_MIN = 100;
    private static final int THINK_MAX = 250;
    private static final String TAG = "MainActivity";
    private static final int ONNX_IMPORT_REQUEST = 7001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                File dir = getExternalFilesDir(null);
                if (dir == null) dir = getFilesDir();
                File f = new File(dir, "crash.txt");
                FileWriter w = new FileWriter(f, true);
                w.write(Log.getStackTraceString(e));
                w.write("\n");
                w.close();
            } catch (Exception ex) {}
            Log.e(TAG, "FATAL", e);
        });
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        initUI();
        requestAllPermissions();
        initModules();
        checkSpecialPermissions();
    }

    private void initUI() {
        try {
            chatRecycler = findViewById(R.id.chatRecycler);
            inputField = findViewById(R.id.inputField);
            sendButton = findViewById(R.id.sendButton);
            menuButton = findViewById(R.id.menuButton);
            statusText = findViewById(R.id.statusText);
            progressBar = findViewById(R.id.progressBar);
            if (chatRecycler == null) return;
            chatAdapter = new ChatAdapter(messages);
            chatRecycler.setAdapter(chatAdapter);
            chatRecycler.setLayoutManager(new LinearLayoutManager(this));
            if (sendButton!= null) sendButton.setOnClickListener(v -> onSendClicked());
            if (menuButton!= null) menuButton.setOnClickListener(v -> showMenu());
            if (inputField!= null) inputField.setOnEditorActionListener((v, id, e) -> {
                onSendClicked();
                return true;
            });
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
                cognitiveMemory = new CognitiveMemory(new File(getFilesDir(), "aibot_memory"), nn, tokenizer);
                atomSpace = new AtomSpaceLite(new File(getFilesDir(), "aibot_memory"));
                openCog = new OpenCogBridge(atomSpace);
                onaEngine = new OnaEngine(nars, atomSpace);
                cognitiveContext = new CognitiveContext(cognitiveMemory, nars, atomSpace, onaEngine);
                narsTool = new NarsTool(nars, atomSpace);
                huggingFaceHub = new HuggingFaceHub();
                selfLearner = new SelfLearner(nn, tokenizer, nars, weightManager, cognitiveMemory, atomSpace);
                webSearch = new WebSearch();
                webFetch = new WebFetch();
                datasetLoader = new DatasetLoader(weightManager.getDatasetDir());
                copyBundledDatasetIfMissing();
                onnxEngine = new OnnxEngine(MainActivity.this, weightManager);
                // Activate the bundled quantized MiniLM encoder once at startup.
                // It is used for semantic routing/retrieval, never as the chat generator.
                boolean onnxReady = onnxEngine.activateEmbeddedSemanticModel();
                trace("ONNX semantic model " + (onnxReady ? "READY" : "UNAVAILABLE"));
                toolRouter = new ToolRouter(onnxEngine);
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
                    } catch (Exception e) {}
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
                            String intro = "I am " + botName + ", your on-device Android assistant.\n\n" +
                                "I already have a baseline of English, conversation, general knowledge, reasoning, memory, web research, and supported phone controls.\n\n" +
                                "Just talk to me naturally.";
                            addBotMessage(intro);
                            if (userName == null) {
                                convManager.setState(ConversationManager.State.WAITING_NAME);
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
                        mainHandler.postDelayed(() -> ensureCoreKnowledgeTrained(), 800);
                        startOverlayIfPermitted();
                    } catch (Exception e) {
                        Log.e(TAG, "UI post crash", e);
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "initModules FATAL", e);
                setStatus("Init failed: " + e.getMessage());
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
            } else if (lower.startsWith("!hf ")) {
                response = handleHuggingFaceCommand(input.substring(4).trim());
            } else if (lower.startsWith("!nars ")) {
                response = narsTool != null ? narsTool.execute(input.substring(6).trim()) : "NARS is not ready.";
            } else if (lower.startsWith("!cog ")) {
                response = openCog != null ? openCog.execute(input.substring(5).trim()) : "OpenCog bridge is not ready.";
            } else if (lower.startsWith("!opencog ")) {
                response = openCog != null ? openCog.execute(input.substring(9).trim()) : "OpenCog bridge is not ready.";
            } else if (lower.startsWith("!ona ")) {
                response = onaEngine != null ? onaEngine.execute(input.substring(5).trim()) : "ONA agent is not ready.";
            } else if (lower.equals("!stats")) {
                response = buildStats();
            } else if (lower.equals("!save")) {
                if (weightManager!= null) weightManager.saveAll();
                response = "Saved. I'll remember everything.";
            } else if (lower.equals("!reset")) {
                response = handleReset();
            } else if (lower.equals("!help")) {
                response = buildHelp();
            } else if (lower.equals("!overlay") || lower.equals("show bubble")) {
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
                    if (overlayManager!= null && emotionSystem!= null)
                        overlayManager.updateMood(emotionSystem.getMoodEmoji());
                    if (emotionSystem!= null && emotionSystem.shouldShareRandomFact())
                        scheduleProactiveFact();
                } catch (Exception e) {
                    Log.e(TAG, "post response crash", e);
                }
            });
            if (state == ConversationManager.State.NORMAL &&!TaskParser.isDeviceCommand(input)) {
                if (cognitiveContext != null) cognitiveContext.rememberConversation(input, finalResponse);
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
                case NARS_REASON:
                    return narsTool != null ? narsTool.execute(task.argument) : "NARS is not ready.";
                case OPENCOG_REASON:
                    return openCog != null ? openCog.execute(task.argument) : "OpenCog bridge is not ready.";
                case ONA_REASON:
                    return onaEngine != null ? onaEngine.execute(task.argument) : "ONA agent is not ready.";
                case FLASHLIGHT_ON:
                    return deviceController.setFlashlight(true)? "Flashlight is on" : "Couldn't turn on flashlight.";
                case FLASHLIGHT_OFF:
                    return deviceController.setFlashlight(false)? "Flashlight is off." : "Couldn't turn off.";
                case FLASHLIGHT_TOGGLE:
                    boolean newState =!deviceController.isFlashlightOn();
                    deviceController.setFlashlight(newState);
                    return "Flashlight " + (newState? "ON" : "OFF") + ".";
                case WIFI_ON:
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        deviceController.openWifiSettings();
                        return "Opening WiFi settings.";
                    }
                    deviceController.setWifi(true);
                    return "WiFi turned on";
                case WIFI_OFF:
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        deviceController.openWifiSettings();
                        return "Opening WiFi settings.";
                    }
                    deviceController.setWifi(false);
                    return "WiFi turned off.";
                case WIFI_STATUS:
                    return "WiFi is " + (deviceController.isWifiEnabled()? "ON" : "OFF") + ".";
                case BLUETOOTH_ON:
                    deviceController.setBluetooth(true);
                    return "Bluetooth turning on";
                case BLUETOOTH_OFF:
                    deviceController.setBluetooth(false);
                    return "Bluetooth turning off.";
                case BLUETOOTH_STATUS:
                    return "Bluetooth is " + (deviceController.isBluetoothEnabled()? "ON" : "OFF") + ".";
                case DATA_ON:
                case DATA_OFF:
                    deviceController.openMobileDataSettings();
                    return "Opening mobile data settings.";
                case OPEN_APP:
                    boolean opened = deviceController.openApp(task.argument);
                    return opened? "Opening " + task.argument : "Couldn't find " + task.argument;
                case SCREEN_READ:
                    return handleScreenRead();
                case SCREEN_APP:
                    return "You're in " + ScreenReaderService.getFriendlyAppName(ScreenReaderService.getCurrentApp());

                            case SCREEN_WHAT:
                    return handleScreenContext(task.original);
                case BATTERY_STATUS:
                    int bat = deviceController.getBatteryLevel();
                    boolean chg = deviceController.isCharging();
                    return "Battery: " + bat + "%" + (chg? " and charging" : "") + ".";
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
            return "Device task failed: " + e.getMessage();
        }
    }

    private String handleScreenRead() {
        if (!ScreenReaderService.isRunning()) {
            return "Enable: Settings -> Accessibility -> AIBot -> Enable";
        }
        String text = ScreenReaderService.getScreenSummary();
        String app = ScreenReaderService.getFriendlyAppName(ScreenReaderService.getCurrentApp());
        return "I can see you're in " + app + ".\nOn screen:\n" + text;
    }

    private String handleScreenContext(String question) {
        try {
            if (!ScreenReaderService.isRunning()) {
                return "I need screen access: Settings -> Accessibility -> AIBot";
            }
            String screenText = ScreenReaderService.getScreenSummary();
            String app = ScreenReaderService.getFriendlyAppName(ScreenReaderService.getCurrentApp());
            nars.parseAndLearn(screenText);
            tokenizer.learnFromText(screenText);
            String nnResponse = generateFromNNWithContext(question, "In " + app + ". Screen: " + screenText);
            if (nnResponse.length() > 5) return "In " + app + ": " + nnResponse;
            return "I see in " + app + ":\n" + screenText.substring(0, Math.min(300, screenText.length()));
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
                                Log.e(TAG, "overlay callback", e);
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
                return "Grant overlay permission, then I'll float on top!";
            }
            if (overlayManager == null) {
                startOverlayIfPermitted();
                return "Floating bubble activated!";
            } else if (overlayManager.isShowing()) {
                overlayManager.hideBubble();
                return "Bubble hidden. Say 'show bubble' to bring back.";
            } else {
                overlayManager.showBubble(birthStory.getBotName(), emotionSystem.getMoodEmoji());
                return "Bubble shown! Tap me anytime.";
            }
        } catch (Exception e) {
            return "Overlay error: " + e.getMessage();
        }
    }

    private String generateHumanResponse(String input) {
        try {
            trace("TURN input: " + input);

            ToolRouter.Decision decision = toolRouter != null
                ? toolRouter.decide(input)
                : new ToolRouter(null).decide(input);
            trace("ROUTER " + decision.summary());

            String memoryAnswer = null;
            if (decision.memory && cognitiveMemory != null) {
                memoryAnswer = cognitiveMemory.answerMemoryQuestion(input);
            }
            if (memoryAnswer != null) {
                trace("MEMORY DIRECT ANSWER");
                return memoryAnswer;
            }

            String topic = convManager.extractTopic(input);
            userMemory.recordTopic(topic);

            String name = userMemory.detectName(input);
            if (name != null) {
                userMemory.setName(name);
                trace("IDENTITY HANDLER");
                return convManager.buildNameResponse(name);
            }

            String lower = input.toLowerCase().trim();

            if (lower.contains("who are you") || lower.contains("what are you")) {
                trace("IDENTITY HANDLER");
                return birthStory.getSelfIntroduction(
                    birthStory.getBotName(), userMemory.getName());
            }

            if (lower.equals("what's your name") ||
                lower.equals("what is your name") ||
                lower.equals("your name")) {
                trace("IDENTITY HANDLER");
                return "My name is " + birthStory.getBotName() + ".";
            }

            if (lower.contains("what can you do") ||
                lower.contains("what do you do") ||
                lower.contains("how can you help") ||
                lower.contains("what are your capabilities")) {
                trace("CAPABILITY HANDLER");
                return "I can chat, remember relevant conversation, use semantic knowledge, " +
                       "research current information, reason when needed, and control supported phone functions.";
            }

            // Current/fresh questions go to web first. The neural network then
            // turns the retrieved evidence into the final natural response.
            if (decision.web) {
                setStatus("Researching " + topic);
                trace("WEB ROUTE");
                List<WebSearch.SearchResult> results = webSearch.search(input);
                if (!results.isEmpty()) {
                    String evidence = webSearch.summarizeResults(results);
                    selfLearner.learnFromWebResults(webSearch.extractFacts(results));
                    for (WebSearch.SearchResult x : results) {
                        if (nars != null) nars.parseAndLearn(x.snippet);
                        if (openCog != null) openCog.learn(x.snippet);
                    }

                    String nnWeb = generateFromNNWithContext(
                        input, "Current web evidence:\n" + evidence);
                    if (isUsableNeuralResponse(nnWeb, input)) {
                        trace("WEB EVIDENCE -> NN");
                        return convManager.buildNaturalResponse(
                            personalityEngine.styleResponse(
                                nnWeb, input, emotionSystem.getMood()),
                            topic, true, false);
                    }
                    trace("WEB NN FALLBACK");
                    return evidence;
                }
                trace("WEB EMPTY");
            }

            // Only retrieve the cognitive systems selected by the router.
            String cognitive = cognitiveContext != null
                ? cognitiveContext.retrieve(
                    input,
                    decision.memory,
                    decision.semantic,
                    decision.reasoning,
                    decision.device)
                : "";
            trace("COGNITIVE CONTEXT chars=" + cognitive.length());

            if (decision.reasoning) {
                String reasoning = narsTool != null
                    ? narsTool.execute(input) : "";
                if (reasoning != null && !reasoning.startsWith("NARS could not")) {
                    cognitive = cognitive + (cognitive.isEmpty() ? "" : "\n") +
                        "Explicit reasoning result:\n" + reasoning;
                    trace("NARS ROUTE");
                }
            }

            String nnR = generateFromNNWithContext(input, cognitive);
            if (isUsableNeuralResponse(nnR, input)) {
                trace("NN RESPONSE accepted chars=" + nnR.length());
                return convManager.buildNaturalResponse(
                    personalityEngine.styleResponse(
                        nnR, input, emotionSystem.getMood()),
                    topic, true, !decision.web);
            }

            trace("NN RESPONSE rejected: " +
                (nnR == null ? "null" : ("chars=" + nnR.length())));

            // Do not web-search ordinary conversation. The template is only a
            // last-resort safety fallback when the custom NN has no usable text.
            trace("TEMPLATE FALLBACK");
            return convManager.buildCasualResponse(input);
        } catch (Exception e) {
            trace("NN TURN ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return convManager.buildCasualResponse(input);
        }
    }

    private String generateFromNN(String input) {
        return generateFromNNWithContext(input, cognitiveMemory != null ? cognitiveMemory.buildContext(input, 2) : "");
    }

    private String generateFromNNWithContext(String input, String context) {
        long started = System.currentTimeMillis();
        try {
            if (nn == null || tokenizer == null) {
                trace("NN NOT READY");
                return "";
            }

            // Keep the user's input at the end of the prompt. The old code could
            // let a long cognitive context push the actual question out of the
            // 128-token Transformer window.
            int[] inputTokens = tokenizer.encode(input, true, false);
            int[] contextTokens = context == null || context.isEmpty()
                ? new int[0] : tokenizer.encode(context, false, false);

            int maxContext = Math.max(0,
                NeuralNetwork.MAX_SEQ_LEN - Math.min(inputTokens.length, 60) - 1);
            if (contextTokens.length > maxContext) {
                contextTokens = Arrays.copyOfRange(
                    contextTokens, contextTokens.length - maxContext, contextTokens.length);
            }

            int inputLimit = Math.min(inputTokens.length, 60);
            if (inputTokens.length > inputLimit) {
                inputTokens = Arrays.copyOfRange(
                    inputTokens, inputTokens.length - inputLimit, inputTokens.length);
            }

            int[] prompt = new int[contextTokens.length + inputTokens.length];
            System.arraycopy(contextTokens, 0, prompt, 0, contextTokens.length);
            System.arraycopy(inputTokens, 0, prompt, contextTokens.length, inputTokens.length);

            trace("NN RUN promptTokens=" + prompt.length +
                  " contextTokens=" + contextTokens.length +
                  " inputTokens=" + inputTokens.length);

            StringBuilder sb = new StringBuilder();
            int[] cur = prompt;
            for (int i = 0; i < 18; i++) {
                int next = nn.generateNextToken(cur, 0.72f);
                if (next == Tokenizer.EOS_TOKEN || next == Tokenizer.PAD_TOKEN) break;
                String w = tokenizer.decodeToken(next);
                if (w == null || w.startsWith("<")) break;
                if (sb.length() > 0) sb.append(" ");
                sb.append(w);

                int[] ext = new int[cur.length + 1];
                System.arraycopy(cur, 0, ext, 0, cur.length);
                ext[cur.length] = next;
                cur = ext;
                if (cur.length > NeuralNetwork.MAX_SEQ_LEN)
                    cur = Arrays.copyOfRange(cur, cur.length - NeuralNetwork.MAX_SEQ_LEN, cur.length);
            }

            String result = sb.toString().trim();
            trace("NN GENERATED tokens=" + result.split("\\s+").length +
                  " chars=" + result.length() +
                  " ms=" + (System.currentTimeMillis() - started));
            return result;
        } catch (Exception e) {
            trace("NN GENERATION ERROR: " + e.getClass().getSimpleName() +
                  ": " + e.getMessage());
            return "";
        }
    }

    private String handleSearchConfirmation(String input) {
        String topic = convManager.getPendingTopic();
        String query = convManager.getPendingSearchQuery();
        convManager.clearPending();
        if (convManager.isYes(input)) {
            setStatus("Searching " + topic);
            List<WebSearch.SearchResult> r = webSearch.search(query);
            if (r.isEmpty()) return "Nothing for " + topic;
            String s = webSearch.summarizeResults(r);
            selfLearner.learnFromWebResults(webSearch.extractFacts(r));
            for (WebSearch.SearchResult x : r) { nars.parseAndLearn(x.snippet); if (openCog != null) openCog.learn(x.snippet); }
            return convManager.buildLearnedFromWebResponse(topic, s);
        }
        if (convManager.isNo(input)) return convManager.buildSearchDeclinedResponse(topic);
        convManager.setPendingSearch(query, topic);
        return "Search for " + topic + "? yes/no";
    }

    private String handleWebSearch(String q) {
        setStatus("Searching " + q);
        List<WebSearch.SearchResult> r = webSearch.search(q);
        if (r.isEmpty()) return "Nothing for " + q;
        String s = webSearch.summarizeResults(r);
        selfLearner.learnFromWebResults(webSearch.extractFacts(r));
        for (WebSearch.SearchResult x : r) { nars.parseAndLearn(x.snippet); if (openCog != null) openCog.learn(x.snippet); }
        emotionSystem.onLearnedSomethingNew();
        return s;
    }

    private String handleWebFetch(String url) {
        setStatus("Fetching");
        String[] r = webFetch.fetch(url);
        tokenizer.learnFromText(r[1]);
        nars.parseAndLearn(r[1]);
        selfLearner.learnFromWebResults(Collections.singletonList(r[1]));
        emotionSystem.onLearnedSomethingNew();
        return r[0] + "\n" + r[1].substring(0, Math.min(400, r[1].length()));
    }

    private String handleNameInput(String input) {
        String n = input.trim();
        if (!n.isEmpty()) n = Character.toUpperCase(n.charAt(0)) + n.substring(1);
        userMemory.setName(n);
        return convManager.buildNameResponse(n);
    }

    private void ensureCoreKnowledgeTrained() {
        try {
            if (selfLearner == null || datasetLoader == null) return;
            android.content.SharedPreferences p = getSharedPreferences("brain_state", MODE_PRIVATE);
            if (p.getBoolean("core_trained_v3", false)) return;

            File core = new File(weightManager.getDatasetDir(), "core_assistant.jsonl");
            if (!core.exists()) copyBundledDatasetIfMissing();
            if (!core.exists()) return;

            addBotMessage("Building my built-in language and knowledge base...");
            if (progressBar != null) {
                progressBar.setVisibility(View.VISIBLE);
                progressBar.setIndeterminate(true);
            }
            datasetLoader.loadAsync(core.getName(), new DatasetLoader.LoadCallback() {
                public void onProgress(int loaded, int total, String file) {
                    mainHandler.post(() -> setStatus("Reading baseline " + loaded));
                }
                public void onComplete(List<DatasetLoader.TrainingSample> samples) {
                    selfLearner.learnFromDataset(samples, new SelfLearner.LearningCallback() {
                        public void onProgress(int step, int total, float loss, String status) {
                            mainHandler.post(() -> {
                                if (progressBar != null) {
                                    progressBar.setIndeterminate(false);
                                    progressBar.setMax(Math.max(1,total));
                                    progressBar.setProgress(Math.min(step,total));
                                }
                                setStatus("Baseline " + step + "/" + total + " | loss " +
                                    String.format(java.util.Locale.US, "%.4f", loss));
                            });
                        }
                        public void onComplete(float avg, int steps) {
                            p.edit().putBoolean("core_trained_v3", true).apply();
                            mainHandler.post(() -> {
                                if (progressBar != null) progressBar.setVisibility(View.GONE);
                                addBotMessage("Baseline ready. Language, conversation, general knowledge, reasoning patterns, identity, and device behavior are loaded.");
                                setStatus(getMoodStatus());
                            });
                        }
                        public void onError(String error) {
                            mainHandler.post(() -> {
                                if (progressBar != null) progressBar.setVisibility(View.GONE);
                                setStatus("Baseline training error");
                            });
                        }
                    });
                }
                public void onError(String error) {
                    mainHandler.post(() -> {
                        if (progressBar != null) progressBar.setVisibility(View.GONE);
                        setStatus("Baseline load error");
                    });
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "core training", e);
        }
    }

    private boolean isUsableNeuralResponse(String response, String input) {
        if (response == null) return false;
        String r = response.trim();
        if (r.length() < 8 || r.length() > 600) return false;
        if (r.contains("<UNK>") || r.contains("<PAD>") || r.contains("<BOS>") || r.contains("<EOS>")) return false;
        String[] words = r.toLowerCase(java.util.Locale.US).split("\\s+");
        if (words.length < 2) return false;
        int unique = new java.util.HashSet<>(java.util.Arrays.asList(words)).size();
        if (unique < Math.max(2, words.length / 4)) return false;
        return !r.equalsIgnoreCase(input.trim());
    }

    private String handleHuggingFaceCommand(String command) {
        if (huggingFaceHub == null) return "Hugging Face is not ready.";
        String[] p = command.trim().split("\\s+", 3);
        if (p.length < 2) return "Use !hf model <query>, !hf dataset <query>, !hf files <model|dataset> <org/name>, or !hf download <model|dataset> <org/name> <file>.";
        String action=p[0].toLowerCase(java.util.Locale.US);
        try {
            if ("model".equals(action)) {
                List<HuggingFaceHub.Item> items=huggingFaceHub.searchModels(command.substring(6).trim(),8);
                return formatHubItems("Models",items);
            }
            if ("dataset".equals(action)) {
                List<HuggingFaceHub.Item> items=huggingFaceHub.searchDatasets(command.substring(8).trim(),8);
                return formatHubItems("Datasets",items);
            }
            if ("files".equals(action) && p.length >= 3) {
                String[] q=p[1].split("\\s+",2);
                List<String> files=huggingFaceHub.listFiles(p[2].trim(),q[0]);
                StringBuilder sb=new StringBuilder("Hub files:\\n");
                for(String file:files) sb.append("• ").append(file).append("\\n");
                return sb.toString();
            }
            if ("download".equals(action) && p.length >= 3) {
                String[] rest=p[2].split("\\s+",2);
                if(rest.length<2) return "Add the repository id and filename.";
                boolean dataset="dataset".equalsIgnoreCase(p[1]);
                File destDir=dataset?weightManager.getDatasetDir():onnxEngine==null?getFilesDir():new File(onnxEngine.getModelPath());
                File dest=new File(destDir,new File(rest[1]).getName());
                if(dataset) huggingFaceHub.downloadDataset(rest[0],rest[1],dest);
                else huggingFaceHub.downloadModel(rest[0],rest[1],dest);
                return "Downloaded " + dest.getName() + " to " + dest.getParent();
            }
        } catch(Exception e) {
            return "Hugging Face error: " + e.getMessage();
        }
        return "Unknown !hf command.";
    }

    private String formatHubItems(String title, List<HuggingFaceHub.Item> items) {
        if(items.isEmpty()) return "No Hub results.";
        StringBuilder sb=new StringBuilder(title).append(":\\n");
        for(HuggingFaceHub.Item item:items) sb.append("• ").append(item.toString()).append("\\n");
        return sb.toString().trim();
    }

    private void copyBundledDatasetIfMissing() {
        try {
            if (weightManager == null || weightManager.getDatasetDir() == null) return;
            File dest = new File(weightManager.getDatasetDir(), "core_assistant.jsonl");
            if (dest.exists() && dest.length() > 100) return;
            if (dest.getParentFile()!= null &&!dest.getParentFile().exists())
                dest.getParentFile().mkdirs();
            int resId = R.raw.core_assistant;
            InputStream is = getResources().openRawResource(resId);
            FileOutputStream fos = new FileOutputStream(dest);
            byte[] buf = new byte[4096];
            int len;
            while ((len = is.read(buf)) > 0) fos.write(buf, 0, len);
            is.close();
            fos.close();
        } catch (Exception e) {
            Log.e(TAG, "copy dataset", e);
        }
    }

    private String handleListDatasets() {
        List<String> ds = datasetLoader.listDatasets();
        if (ds.isEmpty()) return "No datasets in " + weightManager.getDatasetPath();
        StringBuilder sb = new StringBuilder("Datasets:\n");
        for (String d : ds) sb.append("• ").append(d).append("\n");
        return sb.toString();
    }

    private void handleLoadDataset(String filename) {
        mainHandler.post(() -> {
            addBotMessage("Loading " + filename);
            if (progressBar!= null) {
                progressBar.setVisibility(View.VISIBLE);
                progressBar.setIndeterminate(true);
                progressBar.setProgress(0);
            }
        });
        datasetLoader.loadAsync(filename, new DatasetLoader.LoadCallback() {
            public void onProgress(int l, int t, String f) {
                mainHandler.post(() -> setStatus("Loaded " + l));
            }
            public void onComplete(List<DatasetLoader.TrainingSample> s) {
                mainHandler.post(() -> {
                    addBotMessage("Got " + s.size() + " samples. Indexing memory and training...");
                    if (progressBar != null) {
                        progressBar.setIndeterminate(false);
                        progressBar.setMax(Math.max(1, s.size()));
                        progressBar.setProgress(0);
                    }
                });
                selfLearner.learnFromDataset(s, new SelfLearner.LearningCallback() {
                    public void onProgress(int a, int b, float loss, String st) {
                        mainHandler.post(() -> {
                            setStatus(st + " Loss " + String.format(java.util.Locale.US, "%.4f", loss));
                            if (progressBar != null && b > 0) {
                                progressBar.setIndeterminate(false);
                                progressBar.setMax(b);
                                progressBar.setProgress(Math.min(a, b));
                            }
                        });
                    }
                    public void onComplete(float avg, int steps) {
                        mainHandler.post(() -> {
                            if (progressBar!= null) progressBar.setVisibility(View.GONE);
                            addBotMessage("Done! Steps " + steps);
                            if (sendButton!= null) sendButton.setEnabled(true);
                            setStatus(getMoodStatus());
                        });
                    }
                    public void onError(String e) {
                        mainHandler.post(() -> {
                            if (progressBar!= null) progressBar.setVisibility(View.GONE);
                            addBotMessage("Train error " + e);
                        });
                    }
                });
            }
            public void onError(String e) {
                mainHandler.post(() -> {
                    if (progressBar!= null) progressBar.setVisibility(View.GONE);
                    addBotMessage("Load error " + e);
                });
            }
        });
    }

    private String handleReset() {
        weightManager.resetAll();
        nn = new NeuralNetwork();
        tokenizer = new Tokenizer();
        nars = new NARSEngine();
        weightManager = new WeightManager(MainActivity.this, nn, tokenizer, nars);
        cognitiveMemory = new CognitiveMemory(new File(getFilesDir(), "aibot_memory"), nn, tokenizer);
        atomSpace = new AtomSpaceLite(new File(getFilesDir(), "aibot_memory"));
        openCog = new OpenCogBridge(atomSpace);
        onaEngine = new OnaEngine(nars, atomSpace);
        narsTool = new NarsTool(nars, atomSpace);
        huggingFaceHub = new HuggingFaceHub();
        selfLearner = new SelfLearner(nn, tokenizer, nars, weightManager, cognitiveMemory, atomSpace);
        datasetLoader = new DatasetLoader(weightManager.getDatasetDir());
        onnxEngine = new OnnxEngine(MainActivity.this, weightManager);
        personalityEngine = new PersonalityEngine(onnxEngine, tokenizer);
        convManager.clearPending();
        getSharedPreferences("brain_state", MODE_PRIVATE).edit().remove("core_trained_v2").remove("core_trained_v3").apply();
        return "Brain wiped. The built-in baseline will rebuild automatically.";
    }

    private void showMenu() {
        String bot = birthStory!= null? birthStory.getBotName() : "AIBot";
        String[] opts = {"Load Dataset", "Hugging Face", "Web Search", "Fetch URL", "Device Status", "Screen Reader Setup", "Overlay Bubble", "Load ONNX", "Neural Trace", "View Stats", "Save Brain", "Reset Brain", "Help"};
        new AlertDialog.Builder(this).setTitle(bot).setItems(opts, (d, i) -> {
            switch (i) {
                case 0: showDatasetPicker(); break;
                case 1: showHuggingFaceDialog(); break;
                case 2: showSearchDialog(); break;
                case 3: showFetchDialog(); break;
                case 4: addBotMessage(deviceController.getDeviceStatus()); break;
                case 5: showAccessibilitySetup(); break;
                case 6: addBotMessage(handleOverlayToggle()); break;
                case 7: showOnnxPicker(); break;
                case 8: showNeuralTrace(); break;
                case 9: showStats(); break;
                case 10: if (weightManager!= null) weightManager.saveAll(); Toast.makeText(this, "Saved!", 0).show(); break;
                case 11: confirmReset(); break;
                case 12: addBotMessage(buildHelp()); break;
            }
        }).show();
    }

    private void trace(String message) {
        String line = "[" + new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            .format(new java.util.Date()) + "] " + message;
        Log.d(TAG, line);
        synchronized (neuralTrace) {
            neuralTrace.append(line).append("\\n");
            if (neuralTrace.length() > TRACE_MAX)
                neuralTrace.delete(0, neuralTrace.length() - TRACE_MAX);
        }
        mainHandler.post(() -> {
            if (statusText != null && message.startsWith("NN "))
                statusText.setText(message);
        });
    }

    private void showNeuralTrace() {
        final TextView tv = new TextView(this);
        tv.setTextSize(12);
        tv.setTextColor(android.graphics.Color.WHITE);
        tv.setPadding(24, 16, 24, 16);
        tv.setText(neuralTrace.length() == 0
            ? "No neural trace yet. Send a normal message first."
            : neuralTrace.toString());
        tv.setTextIsSelectable(true);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(tv);
        new AlertDialog.Builder(this)
            .setTitle("Neural Trace")
            .setView(scroll)
            .setPositiveButton("Close", null)
            .setNeutralButton("Clear", (d, w) -> {
                synchronized (neuralTrace) { neuralTrace.setLength(0); }
            })
            .show();
    }

    private void showHuggingFaceDialog() {
        EditText et = new EditText(this);
        et.setHint("model or dataset search");
        new AlertDialog.Builder(this)
            .setTitle("Hugging Face Hub")
            .setView(et)
            .setPositiveButton("Search", (d,w) -> {
                String q=et.getText().toString().trim();
                if(q.isEmpty()) return;
                new Thread(() -> {
                    try {
                        List<HuggingFaceHub.Item> models=huggingFaceHub.searchModels(q,5);
                        List<HuggingFaceHub.Item> data=huggingFaceHub.searchDatasets(q,5);
                        StringBuilder sb=new StringBuilder("Models:\n");
                        for(HuggingFaceHub.Item x:models) sb.append("• ").append(x).append("\n");
                        sb.append("\nDatasets:\n");
                        for(HuggingFaceHub.Item x:data) sb.append("• ").append(x).append("\n");
                        mainHandler.post(() -> addBotMessage(sb.toString().trim()));
                    } catch(Exception e) {
                        mainHandler.post(() -> addBotMessage("Hugging Face error: "+e.getMessage()));
                    }
                }).start();
            })
            .setNegativeButton("Close",null).show();
    }

    private void showDatasetPicker() {
        List<String> ds = datasetLoader.listDatasets();
        if (ds.isEmpty()) {
            Toast.makeText(this, "No datasets", 1).show();
            return;
        }
        String[] arr = ds.toArray(new String[0]);
        new AlertDialog.Builder(this).setTitle("Pick").setItems(arr, (d, i) -> handleLoadDataset(arr[i])).show();
    }

    private void showSearchDialog() {
        EditText et = new EditText(this);
        et.setHint("Search");
        new AlertDialog.Builder(this).setTitle("Search").setView(et).setPositiveButton("Search", (d, w) -> {
            String q = et.getText().toString().trim();
            if (!q.isEmpty()) {
                addUserMessage("!search " + q);
                new Thread(() -> {
                    String r = handleWebSearch(q);
                    mainHandler.post(() -> addBotMessage(r));
                }).start();
            }
        }).setNegativeButton("Cancel", null).show();
    }

    private void showFetchDialog() {
        EditText et = new EditText(this);
        et.setHint("https://");
        new AlertDialog.Builder(this).setTitle("Fetch").setView(et).setPositiveButton("Fetch", (d, w) -> {
            String url = et.getText().toString().trim();
            if (!url.isEmpty()) {
                addUserMessage("!fetch " + url);
                new Thread(() -> {
                    String r = handleWebFetch(url);
                    mainHandler.post(() -> addBotMessage(r));
                }).start();
            }
        }).setNegativeButton("Cancel", null).show();
    }

    private void showAccessibilitySetup() {
        String st = ScreenReaderService.isRunning()? "Active" : "Not enabled";
        new AlertDialog.Builder(this).setTitle("Screen Reader").setMessage(st + "\nSettings -> Accessibility -> AIBot -> Enable").setPositiveButton("Open Settings", (d, w) -> {
            Intent i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        }).setNegativeButton("Close", null).show();
    }

    private void showOnnxPicker() {
        if (onnxEngine == null) {
            addBotMessage("ONNX engine is not ready yet.");
            return;
        }

        // Enable ONNX automatically when the user opens its menu.
        if (!onnxEngine.isEnabled()) onnxEngine.setEnabled(true);

        List<String> m = onnxEngine.listAvailableModels();
        String[] arr = m.toArray(new String[0]);

        new AlertDialog.Builder(this)
            .setTitle("ONNX Models")
            .setItems(arr, (d, i) -> {
                String selected = arr[i];

                if ("Import ONNX from storage".equals(selected)) {
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("application/octet-stream");
                    startActivityForResult(intent, ONNX_IMPORT_REQUEST);
                    return;
                }

                if ("Disable ONNX Runtime".equals(selected)) {
                    onnxEngine.setEnabled(false);
                    addBotMessage("ONNX Runtime disabled.");
                    return;
                }

                if ("__enable_onnx__".equals(selected)) {
                    onnxEngine.setEnabled(true);
                    addBotMessage("ONNX Runtime enabled.");
                    return;
                }

                addBotMessage("Loading " + selected + "...");
                setStatus("Downloading/loading " + selected);

                // Model download and session creation must never run on the UI thread.
                new Thread(() -> {
                    boolean loaded = false;
                    String error = null;
                    try {
                        loaded = personalityEngine.loadModel(selected);
                        if (!loaded) error = "Model could not be loaded.";
                    } catch (Throwable e) {
                        error = e.getClass().getSimpleName() + ": " + e.getMessage();
                    }

                    final boolean ok = loaded;
                    final String err = error;
                    mainHandler.post(() -> {
                        addBotMessage(ok
                            ? "Loaded " + selected + " from " + onnxEngine.getModelPath()
                            : "Failed " + selected + (err != null ? "\n" + err : ""));
                        setStatus(getMoodStatus());
                    });
                }).start();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != ONNX_IMPORT_REQUEST || resultCode != RESULT_OK ||
            data == null || data.getData() == null || onnxEngine == null) {
            return;
        }

        Uri uri = data.getData();
        addBotMessage("Copying ONNX model into AIBot...");
        new Thread(() -> {
            String name = onnxEngine.importModel(uri);
            final String imported = name;
            mainHandler.post(() -> {
                if (imported == null) {
                    addBotMessage("ONNX import failed.");
                    return;
                }

                addBotMessage("Saved " + imported + " to " + onnxEngine.getModelPath());

                new Thread(() -> {
                    boolean loaded = false;
                    try {
                        loaded = personalityEngine.loadModel(imported);
                    } catch (Throwable ignored) {}

                    final boolean ok = loaded;
                    mainHandler.post(() ->
                        addBotMessage(ok
                            ? "Loaded " + imported + " and ready to run."
                            : "Saved " + imported + ", but this model could not be loaded. Check that it is a compatible ONNX model.")
                    );
                }).start();
            });
        }).start();
    }

    private void showStats() {
        String bot = birthStory!= null? birthStory.getBotName() : "AIBot";
        new AlertDialog.Builder(this).setTitle(bot + " Stats").setMessage((weightManager!= null? weightManager.getInfo() : "") + "\n" + (nars!= null? nars.getStats() : "") + "\n" + (userMemory!= null? userMemory.getStats() : "")).setPositiveButton("OK", null).show();
    }

    private void confirmReset() {
        new AlertDialog.Builder(this).setTitle("Reset?").setMessage("Forget everything?").setPositiveButton("Reset", (d, w) -> addBotMessage(handleReset())).setNegativeButton("Cancel", null).show();
    }

    private String buildStats() {
        return (weightManager!= null? weightManager.getInfo() : "")
            + "\n" + (cognitiveMemory != null ? cognitiveMemory.getStats() : "")
            + "\n" + (atomSpace != null ? atomSpace.stats() : "") +
            "\n" + (openCog != null ? openCog.stats() : "") +
            "\n" + (onaEngine != null ? onaEngine.stats() : "")
            + "\nMood: " + (emotionSystem!= null? emotionSystem.getMood() : "");
    }

    private String buildHelp() {
        return "I am AIBot. Commands:\n!search query\n!fetch url\n!nars question\n!cog question\n!opencog question\n!ona goal/reasoning task\n!hf model query\n!hf dataset query\n!hf files model org/name\n!hf files dataset org/name\n!hf download model org/name file.onnx\n!hf download dataset org/name file.jsonl\n!datasets\n!load file\n!stats\n!save\n!reset\nshow bubble\nTurn on flashlight\nWhat's on screen?";
    }

    private String getMoodStatus() {
        try {
            String n = birthStory!= null? birthStory.getBotName() : "AIBot";
            String e = emotionSystem!= null? emotionSystem.getMoodEmoji() : "";
            String i = weightManager!= null? weightManager.getInfo() : "";
            String onnx = onnxEngine != null && onnxEngine.isSemanticModelLoaded()
                ? "ONNX semantic ready" : "ONNX semantic off";
            return n + " " + e + " | " + onnx + " | " + i;
        } catch (Exception e) {
            return "AIBot";
        }
    }

    private void addUserMessage(String text) {
        if (chatRecycler == null || chatAdapter == null) return;
        mainHandler.post(() -> {
            messages.add(new ChatMessage(text, true));
            chatAdapter.notifyItemInserted(messages.size() - 1);
            chatRecycler.scrollToPosition(messages.size() - 1);
        });
    }

    private void addBotMessage(String text) {
        mainHandler.post(() -> {
            try {
                if (chatRecycler == null || chatAdapter == null) {
                    messages.add(new ChatMessage(text, false));
                    return;
                }
                messages.add(new ChatMessage(text, false));
                chatAdapter.notifyItemInserted(messages.size() - 1);
                chatRecycler.scrollToPosition(messages.size() - 1);
            } catch (Exception e) {}
        });
    }

    private void setStatus(String s) {
        mainHandler.post(() -> {
            if (statusText!= null) statusText.setText(s);
        });
    }

    private void requestAllPermissions() {
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            needed.add(Manifest.permission.BLUETOOTH_CONNECT);
        List<String> toReq = new ArrayList<>();
        for (String p : needed)
            if (ContextCompat.checkSelfPermission(this, p)!= PackageManager.PERMISSION_GRANTED)
                toReq.add(p);
        if (!toReq.isEmpty())
            ActivityCompat.requestPermissions(this, toReq.toArray(new String[0]), 100);
    }

    private void checkSpecialPermissions() {
        try {
            if (!OverlayManager.hasOverlayPermission(this))
                new Handler(Looper.getMainLooper()).postDelayed(() -> addBotMessage("Tip: Enable overlay bubble in Menu"), 3000);
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

    private void scheduleProactiveFact() {}
}
