import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.NULL;

import imgui.ImFontAtlas;
import imgui.ImFontConfig;
import imgui.ImGui;
import imgui.ImGuiIO;
import imgui.ImGuiStyle;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiConfigFlags;
import imgui.gl3.ImGuiGLES30;
import imgui.glfw.ImGuiGLFW;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.IntBuffer;
import java.util.Objects;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengles.GLES;
import org.lwjgl.opengles.GLES30;
import org.lwjgl.system.Configuration;
import org.lwjgl.system.MemoryStack;

@SuppressWarnings("MagicNumber")
public final class ImGuiGlfwExample {
    private long windowPtr; // pointer to the current GLFW window

    // LWJGJ3 window backend
    private final ImGuiGLFW imGuiGlfw = new ImGuiGLFW();

    // LWJGL3 renderer (SHOULD be initialized)
    private final ImGuiGLES30 imGuiGl3 = new ImGuiGLES30();

    // User UI to render
    private final ExampleUi exampleUi = new ExampleUi();

    public void run() throws Exception {
        setupGlfw();
        setupImGui();

        // Method initializes GLFW backend.
        // This method SHOULD be called after you've setup GLFW.
        // ImGui context should be created as well.
        imGuiGlfw.init(windowPtr, true);
        // Method initializes LWJGL3 renderer.
        // This method SHOULD be called after you've initialized your ImGui configuration (fonts and so on).
        // ImGui context should be created as well.
        imGuiGl3.init();

        loop();

        // You should clean up after yourself in reverse order.
        imGuiGl3.dispose();
        imGuiGlfw.dispose();

        ImGui.destroyContext();

        disposeWindow();
    }

    // Initialize GLFW + create an OpenGL context.
    // All code is mostly a copy-paste from the official LWJGL3 "Get Started": https://www.lwjgl.org/guide
    private void setupGlfw() {
        // Setup an error callback. The default implementation
        // will print the error message in System.err.
        GLFWErrorCallback.createPrint(System.err).set();

        // Initialize GLFW. Most GLFW functions will not work before doing this.
        if (!GLFW.glfwInit()) {
            throw new IllegalStateException("Unable to initialize GLFW");
        }

        // Configure GLFW
        GLFW.glfwDefaultWindowHints(); // optional, the current window hints are already the default
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE); // the window will stay hidden after creation

        decideGlGlslVersions();

        windowPtr = GLFW.glfwCreateWindow(1280, 768, "Dear ImGui+LWJGL Example", NULL, NULL);

        if (windowPtr == NULL) {
            throw new RuntimeException("Failed to create the GLFW window");
        }

        // Get the thread stack and push a new frame
        try (MemoryStack stack = stackPush()) {
            final IntBuffer pWidth = stack.mallocInt(1); // int*
            final IntBuffer pHeight = stack.mallocInt(1); // int*

            // Get the window size passed to GLFW.glfwCreateWindow
            GLFW.glfwGetWindowSize(windowPtr, pWidth, pHeight);

            // Get the resolution of the primary monitor
            final GLFWVidMode vidmode = Objects.requireNonNull(GLFW.glfwGetVideoMode(GLFW.glfwGetPrimaryMonitor()));

            // Center the window
            GLFW.glfwSetWindowPos(windowPtr, (vidmode.width() - pWidth.get(0)) / 2, (vidmode.height() - pHeight.get(0)) / 2);
        } // the stack frame is popped automatically

        GLFW.glfwMakeContextCurrent(windowPtr); // Make the OpenGL context current
        GLFW.glfwSwapInterval(GLFW.GLFW_TRUE); // Enable v-sync
        GLFW.glfwShowWindow(windowPtr); // Make the window visible

        // IMPORTANT!!
        // This line is critical for LWJGL's interoperation with GLFW's
        // OpenGL context, or any context that is managed externally.
        // LWJGL detects the context that is current in the current thread,
        // creates the GLCapabilities instance and makes the OpenGL
        // bindings available for use.
        Configuration.OPENGLES_EXPLICIT_INIT.set(true);
        GLES.create(GL.getFunctionProvider());
        GLES.createCapabilities();
    }

    private void decideGlGlslVersions() {
        GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API, GLFW.GLFW_OPENGL_ES_API);

        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 1);

        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_ANY_PROFILE);
    }

    // Initialize Dear ImGui.
    private void setupImGui() {
        // IMPORTANT!!
        // This line is critical for Dear ImGui to work.
        ImGui.createContext();

        // ------------------------------------------------------------
        // Initialize ImGuiIO config
        final ImGuiIO io = ImGui.getIO();

        io.setIniFilename(null); // We don't want to save .ini file
        io.addConfigFlags(ImGuiConfigFlags.NavEnableKeyboard);  // Enable Keyboard Controls
        io.addConfigFlags(ImGuiConfigFlags.DockingEnable);      // Enable Docking
        io.addConfigFlags(ImGuiConfigFlags.ViewportsEnable);    // Enable Multi-Viewport / Platform Windows
        io.setConfigViewportsNoTaskBarIcon(true);

        // ------------------------------------------------------------
        // Fonts configuration
        // Read: https://raw.githubusercontent.com/ocornut/imgui/master/docs/FONTS.txt

        final ImFontAtlas fontAtlas = io.getFonts();
        final ImFontConfig fontConfig = new ImFontConfig(); // Natively allocated object, should be explicitly destroyed

        // Glyphs could be added per-font as well as per config used globally like here
        fontConfig.setGlyphRanges(fontAtlas.getGlyphRangesCyrillic());

        // Add a default font, which is 'ProggyClean.ttf, 13px'
        fontAtlas.addFontDefault();

        // Fonts merge example
        fontConfig.setMergeMode(true); // When enabled, all fonts added with this config would be merged with the previously added font
        fontConfig.setPixelSnapH(true);

        fontAtlas.addFontFromMemoryTTF(loadFromResources("basis33.ttf"), 16, fontConfig);

        fontConfig.setMergeMode(false);
        fontConfig.setPixelSnapH(false);

        // Fonts from file/memory example
        // We can add new fonts from the file system
        fontAtlas.addFontFromFileTTF("src/test/resources/Righteous-Regular.ttf", 14, fontConfig);
        fontAtlas.addFontFromFileTTF("src/test/resources/Righteous-Regular.ttf", 16, fontConfig);

        // Or directly from the memory
        fontConfig.setName("Roboto-Regular.ttf, 14px"); // This name will be displayed in Style Editor
        fontAtlas.addFontFromMemoryTTF(loadFromResources("Roboto-Regular.ttf"), 14, fontConfig);
        fontConfig.setName("Roboto-Regular.ttf, 16px"); // We can apply a new config value every time we add a new font
        fontAtlas.addFontFromMemoryTTF(loadFromResources("Roboto-Regular.ttf"), 16, fontConfig);

        fontConfig.destroy(); // After all fonts were added we don't need this config more

        // When viewports are enabled we tweak WindowRounding/WindowBg so platform windows can look identical to regular ones.
        if (io.hasConfigFlags(ImGuiConfigFlags.ViewportsEnable)) {
            final ImGuiStyle style = ImGui.getStyle();
            style.setWindowRounding(0.0f);
            style.setColor(ImGuiCol.WindowBg, ImGui.getColorU32(ImGuiCol.WindowBg, 1));
        }
    }

    // Main application loop
    private void loop() throws Exception {
        // Run the rendering loop until the user has attempted to close the window
        while (!GLFW.glfwWindowShouldClose(windowPtr)) {
            startFrame();

            // Any Dear ImGui code SHOULD go between ImGui.newFrame()/ImGui.render() methods
            imGuiGlfw.newFrame();
            ImGui.newFrame();

            exampleUi.render();
            ImGui.render();

            endFrame();
        }
    }

    private void startFrame() {
        // Set the clear color and clear the window
        GLES30.glClearColor(exampleUi.backgroundColor[0], exampleUi.backgroundColor[1], exampleUi.backgroundColor[2], 0.0f);
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
    }

    private void endFrame() {
        // After Dear ImGui prepared a draw data, we use it in the LWJGL3 renderer.
        // At that moment ImGui will be rendered to the current OpenGL context.
        imGuiGl3.renderDrawData(ImGui.getDrawData());

        if (ImGui.getIO().hasConfigFlags(ImGuiConfigFlags.ViewportsEnable)) {
            final long backupWindowPtr = GLFW.glfwGetCurrentContext();
            ImGui.updatePlatformWindows();
            ImGui.renderPlatformWindowsDefault();
            GLFW.glfwMakeContextCurrent(backupWindowPtr);
        }

        GLFW.glfwSwapBuffers(windowPtr);
        GLFW.glfwPollEvents();
    }

    private void disposeWindow() {
//        GLFW.glfwFreeCallbacks(windowPtr);
        GLFW.glfwDestroyWindow(windowPtr);
        GLFW.glfwTerminate();
        Objects.requireNonNull(GLFW.glfwSetErrorCallback(null)).free();
    }

    private byte[] loadFromResources(final String fileName) {
        try (InputStream is = Objects.requireNonNull(getClass().getClassLoader().getResourceAsStream(fileName));
            ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {

            final byte[] data = new byte[16384];

            int nRead;
            while ((nRead = is.read(data, 0, data.length)) != -1) {
                buffer.write(data, 0, nRead);
            }

            return buffer.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static void main(final String[] args) throws Exception {
        new ImGuiGlfwExample().run();
    }
}
