package imgui.gl3;

import imgui.ImDrawData;
import imgui.ImFontAtlas;
import imgui.ImGui;
import imgui.ImGuiIO;
import imgui.ImGuiViewport;
import imgui.ImVec2;
import imgui.ImVec4;
import imgui.callback.ImPlatformFuncViewport;
import imgui.flag.ImGuiConfigFlags;
import imgui.flag.ImGuiViewportFlags;
import imgui.type.ImInt;
import imgui.flag.ImGuiBackendFlags;

import java.nio.ByteBuffer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.lwjgl.opengles.GLES30;

/**
 * This class is a straightforward port of the
 * <a href="https://raw.githubusercontent.com/ocornut/imgui/05bc204dbd80dfebb3dab1511caf1cb980620c76/examples/imgui_impl_opengl3.cpp">imgui_impl_opengl3.cpp</a>.
 * <p>
 * It do support a backup and restoring of the GL state in the same way the original Dear ImGui code does.
 * Some of the very specific OpenGL variables may be ignored here,
 * yet you can copy-paste this class in your codebase and modify the rendering routine in the way you'd like.
 * <p>
 * This implementation has an ability to use a GLSL version provided during the initialization.
 * Please read the documentation for the {@link #init(String)}.
 */
@SuppressWarnings("MagicNumber")
public final class ImGuiImplGl3 {
    // OpenGL Data
    private String glslVersion = "";
    private int gFontTexture = 0;
    private int gShaderHandle = 0;
    private int gVertHandle = 0;
    private int gFragHandle = 0;
    private int gAttribLocationTex = 0;
    private int gAttribLocationProjMtx = 0;
    private int gAttribLocationVtxPos = 0;
    private int gAttribLocationVtxUV = 0;
    private int gAttribLocationVtxColor = 0;
    private int gVboHandle = 0;
    private int gElementsHandle = 0;
    private int gVertexArrayObjectHandle = 0;

    // Used to store tmp renderer data
    private final ImVec2 displaySize = new ImVec2();
    private final ImVec2 framebufferScale = new ImVec2();
    private final ImVec2 displayPos = new ImVec2();
    private final ImVec4 clipRect = new ImVec4();
    private final float[] orthoProjMatrix = new float[4 * 4];

    // Variables used to backup GL state before and after the rendering of Dear ImGui
    private final int[] lastActiveTexture = new int[1];
    private final int[] lastProgram = new int[1];
    private final int[] lastTexture = new int[1];
    private final int[] lastArrayBuffer = new int[1];
    private final int[] lastVertexArrayObject = new int[1];
    private final int[] lastViewport = new int[4];
    private final int[] lastScissorBox = new int[4];
    private final int[] lastBlendSrcRgb = new int[1];
    private final int[] lastBlendDstRgb = new int[1];
    private final int[] lastBlendSrcAlpha = new int[1];
    private final int[] lastBlendDstAlpha = new int[1];
    private final int[] lastBlendEquationRgb = new int[1];
    private final int[] lastBlendEquationAlpha = new int[1];
    private boolean lastEnableBlend = false;
    private boolean lastEnableCullFace = false;
    private boolean lastEnableDepthTest = false;
    private boolean lastEnableScissorTest = false;

    /**
     * Method to do an initialization of the {@link ImGuiImplGl3} state.
     * It SHOULD be called before calling of the {@link ImGuiImplGl3#renderDrawData(ImDrawData)} method.
     * <p>
     * Unlike in the {@link #init(String)} method, here the glslVersion argument is omitted.
     * Thus a "#version 130" string will be used instead.
     */
    public void init() {
        init("430");
    }

    /**
     * Method to do an initialization of the {@link ImGuiImplGl3} state.
     * It SHOULD be called before calling of the {@link ImGuiImplGl3#renderDrawData(ImDrawData)} method.
     * <p>
     * Method takes an argument, which should be a valid GLSL string with the version to use.
     * <pre>
     * ----------------------------------------
     * OpenGL    GLSL      GLSL
     * version   version   string
     * ---------------------------------------
     *  2.0       110       "#version 110"
     *  2.1       120       "#version 120"
     *  3.0       130       "#version 130"
     *  3.1       140       "#version 140"
     *  3.2       150       "#version 150"
     *  3.3       330       "#version 330 core"
     *  4.0       400       "#version 400 core"
     *  4.1       410       "#version 410 core"
     *  4.2       420       "#version 410 core"
     *  4.3       430       "#version 430 core"
     * ---------------------------------------
     * </pre>
     * <p>
     * If the argument is null, then a "#version 130" string will be used by default.
     */
    private void init(final String glslVersion) {
        readGlVersion();
        setupBackendCapabilitiesFlags();

        if (glslVersion == null) {
            this.glslVersion = "#version 130";
        } else {
            this.glslVersion = glslVersion;
        }

        createDeviceObjects();

        if (ImGui.getIO().hasConfigFlags(ImGuiConfigFlags.ViewportsEnable)) {
            initPlatformInterface();
        }
    }

    /**
     * Method to render {@link ImDrawData} into current OpenGL context.
     */
    public void renderDrawData(final ImDrawData drawData) {
        if (drawData.getCmdListsCount() <= 0) {
            return;
        }

        // Will project scissor/clipping rectangles into framebuffer space
        drawData.getDisplaySize(displaySize);           // (0,0) unless using multi-viewports
        drawData.getFramebufferScale(framebufferScale); // (1,1) unless using retina display which are often (2,2)

        // Avoid rendering when minimized, scale coordinates for retina displays (screen coordinates != framebuffer coordinates)
        final int fbWidth = (int) (displaySize.x * framebufferScale.x);
        final int fbHeight = (int) (displaySize.y * framebufferScale.y);

        if (fbWidth <= 0 || fbHeight <= 0) {
            return;
        }

        drawData.getDisplayPos(displayPos);

        backupGlState();
        bind(fbWidth, fbHeight);

        // Render command lists
        for (int cmdListIdx = 0; cmdListIdx < drawData.getCmdListsCount(); cmdListIdx++) {
            // Upload vertex/index buffers
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, drawData.getCmdListVtxBufferData(cmdListIdx), GLES30.GL_STREAM_DRAW);
            GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, drawData.getCmdListIdxBufferData(cmdListIdx), GLES30.GL_STREAM_DRAW);

            for (int cmdBufferIdx = 0; cmdBufferIdx < drawData.getCmdListCmdBufferSize(cmdListIdx); cmdBufferIdx++) {
                drawData.getCmdListCmdBufferClipRect(cmdListIdx, cmdBufferIdx, clipRect);

                final float clipRectX = (clipRect.x - displayPos.x) * framebufferScale.x;
                final float clipRectY = (clipRect.y - displayPos.y) * framebufferScale.y;
                final float clipRectZ = (clipRect.z - displayPos.x) * framebufferScale.x;
                final float clipRectW = (clipRect.w - displayPos.y) * framebufferScale.y;

                if (clipRectX < fbWidth && clipRectY < fbHeight && clipRectZ >= 0.0f && clipRectW >= 0.0f) {
                    // Apply scissor/clipping rectangle
                    GLES30.glScissor((int) clipRectX, (int) (fbHeight - clipRectW), (int) (clipRectZ - clipRectX), (int) (clipRectW - clipRectY));

                    // Bind texture, Draw
                    final int textureId = drawData.getCmdListCmdBufferTextureId(cmdListIdx, cmdBufferIdx);
                    final int elemCount = drawData.getCmdListCmdBufferElemCount(cmdListIdx, cmdBufferIdx);
                    final int idxBufferOffset = drawData.getCmdListCmdBufferIdxOffset(cmdListIdx, cmdBufferIdx);
                    final int vtxBufferOffset = drawData.getCmdListCmdBufferVtxOffset(cmdListIdx, cmdBufferIdx);
                    final int indices = idxBufferOffset * ImDrawData.SIZEOF_IM_DRAW_IDX;

                    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId);

//                    if (GLES30.glVersion >= 320) {
//                        GLES30.glDrawElementsBaseVertex(GLES30.GL_TRIANGLES, elemCount, GLES30.GL_UNSIGNED_SHORT, indices, vtxBufferOffset);
//                    } else {
                        GLES30.glDrawElements(GLES30.GL_TRIANGLES, elemCount, GLES30.GL_UNSIGNED_SHORT, indices);
//                    }
                }
            }
        }

        unbind();
        restoreModifiedGlState();
    }

    /**
     * Call this method in the end of your application cycle to dispose resources used by {@link ImGuiImplGl3}.
     */
    public void dispose() {
        GLES30.glDeleteBuffers(gVboHandle);
        GLES30.glDeleteBuffers(gElementsHandle);
        GLES30.glDetachShader(gShaderHandle, gVertHandle);
        GLES30.glDetachShader(gShaderHandle, gFragHandle);
        GLES30.glDeleteProgram(gShaderHandle);
        GLES30.glDeleteTextures(gFontTexture);
        shutdownPlatformInterface();
    }

    /**
     * Method rebuilds the font atlas for Dear ImGui. Could be used to update application fonts in runtime.
     */
    public void updateFontsTexture() {
        GLES30.glDeleteTextures(gFontTexture);

        final ImFontAtlas fontAtlas = ImGui.getIO().getFonts();
        final ImInt width = new ImInt();
        final ImInt height = new ImInt();
        final ByteBuffer buffer = fontAtlas.getTexDataAsRGBA32(width, height);

        gFontTexture = GLES30.glGenTextures();

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, gFontTexture);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);

        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, width.get(), height.get(), 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buffer);

        fontAtlas.setTexID(gFontTexture);
    }

    private void readGlVersion() {
        final int[] major = new int[1];
        final int[] minor = new int[1];
        GLES30.glGetIntegerv(GLES30.GL_MAJOR_VERSION, major);
        GLES30.glGetIntegerv(GLES30.GL_MINOR_VERSION, minor);
    }

    private void setupBackendCapabilitiesFlags() {
        final ImGuiIO io = ImGui.getIO();
        io.setBackendRendererName("imgui_java_impl_opnegl3");

//        // We can honor the ImDrawCmd::VtxOffset field, allowing for large meshes.
//        if (GLES30.glVersion >= 320) {
//            io.addBackendFlags(ImGuiBackendFlags.RendererHasVtxOffset);
//        }

        // We can create multi-viewports on the Renderer side (optional)
        io.addBackendFlags(ImGuiBackendFlags.RendererHasViewports);
    }

    private void createDeviceObjects() {
        // Backup GL state
        final int[] lastTexture = new int[1];
        final int[] lastArrayBuffer = new int[1];
        final int[] lastVertexArray = new int[1];
        GLES30.glGetIntegerv(GLES30.GL_TEXTURE_BINDING_2D, lastTexture);
        GLES30.glGetIntegerv(GLES30.GL_ARRAY_BUFFER_BINDING, lastArrayBuffer);
        GLES30.glGetIntegerv(GLES30.GL_VERTEX_ARRAY_BINDING, lastVertexArray);

        createShaders();

        gAttribLocationTex = GLES30.glGetUniformLocation(gShaderHandle, "Texture");
        gAttribLocationProjMtx = GLES30.glGetUniformLocation(gShaderHandle, "ProjMtx");
        gAttribLocationVtxPos = GLES30.glGetAttribLocation(gShaderHandle, "Position");
        gAttribLocationVtxUV = GLES30.glGetAttribLocation(gShaderHandle, "UV");
        gAttribLocationVtxColor = GLES30.glGetAttribLocation(gShaderHandle, "Color");

        // Create buffers
        gVboHandle = GLES30.glGenBuffers();
        gElementsHandle = GLES30.glGenBuffers();

        updateFontsTexture();

        // Restore modified GL state
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, lastTexture[0]);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, lastArrayBuffer[0]);
        GLES30.glBindVertexArray(lastVertexArray[0]);
    }

    private void createShaders() {
        final int glslVersionValue = parseGlslVersionString();

        // Select shaders matching our GLSL versions
        final CharSequence vertShaderSource;
        final CharSequence fragShaderSource;

        if (glslVersionValue < 130) {
            vertShaderSource = getVertexShaderGlsl120();
            fragShaderSource = getFragmentShaderGlsl120();
        } else if (glslVersionValue >= 410) {
            vertShaderSource = getVertexShaderGlsl410Core();
            fragShaderSource = getFragmentShaderGlsl410Core();
        } else {
            vertShaderSource = getVertexShaderGlsl130();
            fragShaderSource = getFragmentShaderGlsl130();
        }

        gVertHandle = createAndCompileShader(GLES30.GL_VERTEX_SHADER, vertShaderSource);
        gFragHandle = createAndCompileShader(GLES30.GL_FRAGMENT_SHADER, fragShaderSource);

        gShaderHandle = GLES30.glCreateProgram();
        GLES30.glAttachShader(gShaderHandle, gVertHandle);
        GLES30.glAttachShader(gShaderHandle, gFragHandle);
        GLES30.glLinkProgram(gShaderHandle);

        if (GLES30.glGetProgrami(gShaderHandle, GLES30.GL_LINK_STATUS) == GLES30.GL_FALSE) {
            throw new IllegalStateException("Failed to link shader program:\n" + GLES30.glGetProgramInfoLog(gShaderHandle));
        }
    }

    private int parseGlslVersionString() {
        final Pattern p = Pattern.compile("\\d+");
        final Matcher m = p.matcher(glslVersion);

        if (m.find()) {
            return Integer.parseInt(m.group());
        } else {
            throw new IllegalArgumentException("Invalid GLSL version string: " + glslVersion);
        }
    }

    private void backupGlState() {
        GLES30.glGetIntegerv(GLES30.GL_ACTIVE_TEXTURE, lastActiveTexture);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glGetIntegerv(GLES30.GL_CURRENT_PROGRAM, lastProgram);
        GLES30.glGetIntegerv(GLES30.GL_TEXTURE_BINDING_2D, lastTexture);
        GLES30.glGetIntegerv(GLES30.GL_ARRAY_BUFFER_BINDING, lastArrayBuffer);
        GLES30.glGetIntegerv(GLES30.GL_VERTEX_ARRAY_BINDING, lastVertexArrayObject);
        GLES30.glGetIntegerv(GLES30.GL_VIEWPORT, lastViewport);
        GLES30.glGetIntegerv(GLES30.GL_SCISSOR_BOX, lastScissorBox);
        GLES30.glGetIntegerv(GLES30.GL_BLEND_SRC_RGB, lastBlendSrcRgb);
        GLES30.glGetIntegerv(GLES30.GL_BLEND_DST_RGB, lastBlendDstRgb);
        GLES30.glGetIntegerv(GLES30.GL_BLEND_SRC_ALPHA, lastBlendSrcAlpha);
        GLES30.glGetIntegerv(GLES30.GL_BLEND_DST_ALPHA, lastBlendDstAlpha);
        GLES30.glGetIntegerv(GLES30.GL_BLEND_EQUATION_RGB, lastBlendEquationRgb);
        GLES30.glGetIntegerv(GLES30.GL_BLEND_EQUATION_ALPHA, lastBlendEquationAlpha);
        lastEnableBlend = GLES30.glIsEnabled(GLES30.GL_BLEND);
        lastEnableCullFace = GLES30.glIsEnabled(GLES30.GL_CULL_FACE);
        lastEnableDepthTest = GLES30.glIsEnabled(GLES30.GL_DEPTH_TEST);
        lastEnableScissorTest = GLES30.glIsEnabled(GLES30.GL_SCISSOR_TEST);
    }

    private void restoreModifiedGlState() {
        GLES30.glUseProgram(lastProgram[0]);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, lastTexture[0]);
        GLES30.glActiveTexture(lastActiveTexture[0]);
        GLES30.glBindVertexArray(lastVertexArrayObject[0]);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, lastArrayBuffer[0]);
        GLES30.glBlendEquationSeparate(lastBlendEquationRgb[0], lastBlendEquationAlpha[0]);
        GLES30.glBlendFuncSeparate(lastBlendSrcRgb[0], lastBlendDstRgb[0], lastBlendSrcAlpha[0], lastBlendDstAlpha[0]);
        // @formatter:off CHECKSTYLE:OFF
        if (lastEnableBlend) GLES30.glEnable(GLES30.GL_BLEND); else GLES30.glDisable(GLES30.GL_BLEND);
        if (lastEnableCullFace) GLES30.glEnable(GLES30.GL_CULL_FACE); else GLES30.glDisable(GLES30.GL_CULL_FACE);
        if (lastEnableDepthTest) GLES30.glEnable(GLES30.GL_DEPTH_TEST); else GLES30.glDisable(GLES30.GL_DEPTH_TEST);
        if (lastEnableScissorTest) GLES30.glEnable(GLES30.GL_SCISSOR_TEST); else GLES30.glDisable(GLES30.GL_SCISSOR_TEST);
        // @formatter:on CHECKSTYLE:ON
        GLES30.glViewport(lastViewport[0], lastViewport[1], lastViewport[2], lastViewport[3]);
        GLES30.glScissor(lastScissorBox[0], lastScissorBox[1], lastScissorBox[2], lastScissorBox[3]);
    }

    // Setup desired GL state
    private void bind(final int fbWidth, final int fbHeight) {
        // Recreate the VAO every time (this is to easily allow multiple GL contexts to be rendered to. VAO are not shared among GL contexts)
        // The renderer would actually work without any VAO bound, but then our VertexAttrib calls would overwrite the default one currently bound.
        gVertexArrayObjectHandle = GLES30.glGenVertexArrays();

        // Setup render state: alpha-blending enabled, no face culling, no depth testing, scissor enabled, polygon fill
        GLES30.glEnable(GLES30.GL_BLEND);
        GLES30.glBlendEquation(GLES30.GL_FUNC_ADD);
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA);
        GLES30.glDisable(GLES30.GL_CULL_FACE);
        GLES30.glDisable(GLES30.GL_DEPTH_TEST);
        GLES30.glEnable(GLES30.GL_SCISSOR_TEST);

        // Setup viewport, orthographic projection matrix
        // Our visible imgui space lies from draw_data->DisplayPos (top left) to draw_data->DisplayPos+data_data->DisplaySize (bottom right).
        // DisplayPos is (0,0) for single viewport apps.
        GLES30.glViewport(0, 0, fbWidth, fbHeight);
        final float left = displayPos.x;
        final float right = displayPos.x + displaySize.x;
        final float top = displayPos.y;
        final float bottom = displayPos.y + displaySize.y;

        // Orthographic matrix projection
        orthoProjMatrix[0] = 2.0f / (right - left);
        orthoProjMatrix[5] = 2.0f / (top - bottom);
        orthoProjMatrix[10] = -1.0f;
        orthoProjMatrix[12] = (right + left) / (left - right);
        orthoProjMatrix[13] = (top + bottom) / (bottom - top);
        orthoProjMatrix[15] = 1.0f;

        // Bind shader
        GLES30.glUseProgram(gShaderHandle);
        GLES30.glUniform1i(gAttribLocationTex, 0);
        GLES30.glUniformMatrix4fv(gAttribLocationProjMtx, false, orthoProjMatrix);

        GLES30.glBindVertexArray(gVertexArrayObjectHandle);

        // Bind vertex/index buffers and setup attributes for ImDrawVert
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, gVboHandle);
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, gElementsHandle);
        GLES30.glEnableVertexAttribArray(gAttribLocationVtxPos);
        GLES30.glEnableVertexAttribArray(gAttribLocationVtxUV);
        GLES30.glEnableVertexAttribArray(gAttribLocationVtxColor);
        GLES30.glVertexAttribPointer(gAttribLocationVtxPos, 2, GLES30.GL_FLOAT, false, ImDrawData.SIZEOF_IM_DRAW_VERT, 0);
        GLES30.glVertexAttribPointer(gAttribLocationVtxUV, 2, GLES30.GL_FLOAT, false, ImDrawData.SIZEOF_IM_DRAW_VERT, 8);
        GLES30.glVertexAttribPointer(gAttribLocationVtxColor, 4, GLES30.GL_UNSIGNED_BYTE, true, ImDrawData.SIZEOF_IM_DRAW_VERT, 16);
    }

    private void unbind() {
        // Destroy the temporary VAO
        GLES30.glDeleteVertexArrays(gVertexArrayObjectHandle);
    }

    //--------------------------------------------------------------------------------------------------------
    // MULTI-VIEWPORT / PLATFORM INTERFACE SUPPORT
    // This is an _advanced_ and _optional_ feature, allowing the back-end to create and handle multiple viewports simultaneously.
    // If you are new to dear imgui or creating a new binding for dear imgui, it is recommended that you completely ignore this section first..
    //--------------------------------------------------------------------------------------------------------

    private void initPlatformInterface() {
        ImGui.getPlatformIO().setRendererRenderWindow(new ImPlatformFuncViewport() {
            @Override
            public void accept(final ImGuiViewport vp) {
                if (!vp.hasFlags(ImGuiViewportFlags.NoRendererClear)) {
                    GLES30.glClearColor(0, 0, 0, 0);
                    GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
                }
                renderDrawData(vp.getDrawData());
            }
        });
    }

    private void shutdownPlatformInterface() {
        ImGui.destroyPlatformWindows();
    }

    private int createAndCompileShader(final int type, final CharSequence source) {
        final int id = GLES30.glCreateShader(type);

        GLES30.glShaderSource(id, source);
        GLES30.glCompileShader(id);

        if (GLES30.glGetShaderi(id, GLES30.GL_COMPILE_STATUS) == GLES30.GL_FALSE) {
            throw new IllegalStateException("Failed to compile shader:\n" + GLES30.glGetShaderInfoLog(id));
        }

        return id;
    }

    private String getVertexShaderGlsl120() {
        return "";
    }

    private String getVertexShaderGlsl130() {
        return "";
    }

    private String getVertexShaderGlsl410Core() {
        return "#version 300 es\n"

            + "precision highp float;\n"
            + "precision highp int;\n"
            + "precision lowp sampler2D;\n"
            + "precision lowp samplerCube;\n"

            + "layout (location = 0) in vec2 Position;\n"
            + "layout (location = 1) in vec2 UV;\n"
            + "layout (location = 2) in vec4 Color;\n"

            + "uniform mat4 ProjMtx;\n"

            + "out vec2 Frag_UV;\n"
            + "out vec4 Frag_Color;\n"

            + "void main()\n"
            + "{\n"
            + "    Frag_UV = UV;\n"
            + "    Frag_Color = Color;\n"
            + "    gl_Position = ProjMtx * vec4(Position.xy,0,1);\n"
            + "}\n";
    }

    private String getFragmentShaderGlsl120() {
        return "";
    }

    private String getFragmentShaderGlsl130() {
        return "";
    }

    private String getFragmentShaderGlsl410Core() {
        return "#version 300 es\n"

            + "precision highp float;\n"
            + "precision mediump int;\n"
            + "precision lowp sampler2D;\n"
            + "precision lowp samplerCube;\n"

            + "in vec2 Frag_UV;\n"
            + "in vec4 Frag_Color;\n"

            + "uniform sampler2D Texture;\n"

            + "layout (location = 0) out vec4 Out_Color;\n"

            + "void main()\n"
            + "{\n"
            + "    Out_Color = Frag_Color * texture(Texture, Frag_UV.st);\n"
            + "}\n";
    }
}
