package com.moulberry.flashback.editor.ui.windows;

import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.Utils;
import com.moulberry.flashback.combo_options.AspectRatio;
import com.moulberry.flashback.combo_options.AudioCodec;
import com.moulberry.flashback.combo_options.CompressionLevel;
import com.moulberry.flashback.combo_options.ExportProjection;
import com.moulberry.flashback.combo_options.PixelDepth;
import com.moulberry.flashback.combo_options.ProResProfile;
import com.moulberry.flashback.combo_options.Sizing;
import com.moulberry.flashback.combo_options.VideoCodec;
import com.moulberry.flashback.combo_options.VideoContainer;
import com.moulberry.flashback.configuration.FlashbackConfigV1;
import com.moulberry.flashback.editor.ui.ReplayUI;
import com.moulberry.flashback.exporting.ExportJobQueue;
import com.moulberry.flashback.state.EditorState;
import com.moulberry.flashback.state.EditorStateManager;
import com.moulberry.flashback.editor.ui.ImGuiHelper;
import com.moulberry.flashback.exporting.ExportJob;
import com.moulberry.flashback.exporting.ExportSettings;
import com.moulberry.flashback.exporting.VideoEncoder;
import com.moulberry.flashback.playback.ReplayServer;
import com.moulberry.flashback.utils.AsyncFileDialogs;
import imgui.moulberry90.ImGui;
import imgui.moulberry90.flag.ImGuiWindowFlags;
import imgui.moulberry90.type.ImString;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.FileUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.language.I18n;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

public class StartExportWindow {

    private static boolean open = false;
    private static boolean close = false;

    private static final int[] lastFramebufferSize = new int[]{0, 0};
    private static AspectRatio lastCustomAspectRatio = null;

    private static final int[] startEndTick = new int[]{-1, -1};

    private static VideoContainer[] supportedContainers = null;
    private static VideoContainer[] supportedContainersWithTransparency = null;

    private static final ImString bitrate = ImGuiHelper.createResizableImString("20m");
    private static final ImString jobName = ImGuiHelper.createResizableImString("");
    private static final ImString pngSequenceFormat = ImGuiHelper.createResizableImString("%04d");

    private static String installedIncompatibleModsString = null;
    private static final List<String> potentialIncompatibleMods = List.of(
        "g4mespeed", // causes rendering issues due to overriding partial tick time
        "feather" // causes miscellaneous crashes and issues that are impossible to debug
    );

    static {
        bitrate.inputData.allowedChars = "0123456789kmb";
    }

    public static void render() {
        EditorState editorState = EditorStateManager.getCurrent();

        if (open) {
            installedIncompatibleModsString = null;
            for (String potentialIncompatibleMod : potentialIncompatibleMods) {
                if (FabricLoader.getInstance().isModLoaded(potentialIncompatibleMod)) {
                    if (installedIncompatibleModsString == null) {
                        installedIncompatibleModsString = potentialIncompatibleMod;
                    } else {
                        installedIncompatibleModsString += ", " + potentialIncompatibleMod;
                    }
                }
            }

            FlashbackConfigV1 config = Flashback.getConfig();
            config.forceDefaultExportSettings.apply(config.internalExport);

            if (config.internalExport.resolution == null || config.internalExport.resolution.length != 2) {
                config.internalExport.resolution = new int[]{1920, 1080};
            }
            if (config.internalExport.framerate == null || config.internalExport.framerate.length != 1) {
                config.internalExport.framerate = new float[]{60};
            }
            if (config.internalExport.audioCodec == null) {
                config.internalExport.audioCodec = AudioCodec.AAC;
            }

            ImGui.openPopup("###StartExport");

            if (editorState != null && editorState.replayVisuals.sizing == Sizing.CHANGE_ASPECT_RATIO) {
                AspectRatio aspectRatio = editorState.replayVisuals.changeAspectRatio;
                if (aspectRatio != null && aspectRatio != lastCustomAspectRatio) {
                    switch (aspectRatio) {
                        case ASPECT_16_9 -> {
                            config.internalExport.resolution[0] = 1920;
                            config.internalExport.resolution[1] = 1080;
                        }
                        case ASPECT_9_16 -> {
                            config.internalExport.resolution[0] = 1080;
                            config.internalExport.resolution[1] = 1920;
                        }
                        case ASPECT_240_1 -> {
                            config.internalExport.resolution[0] = 1920;
                            config.internalExport.resolution[1] = 800;
                        }
                        case ASPECT_1_1 -> {
                            config.internalExport.resolution[0] = 1920;
                            config.internalExport.resolution[1] = 1920;
                        }
                        case ASPECT_4_3 -> {
                            config.internalExport.resolution[0] = 1600;
                            config.internalExport.resolution[1] = 1200;
                        }
                        case ASPECT_3_2 -> {
                            config.internalExport.resolution[0] = 2160;
                            config.internalExport.resolution[1] = 1440;
                        }
                    }
                }
                lastCustomAspectRatio = aspectRatio;
            }

            open = false;
            close = false;
        }

        if (ImGuiHelper.beginPopupModalCloseable(I18n.get("flashback.export_to_video") + "###StartExport", ImGuiWindowFlags.AlwaysAutoResize)) {
            if (close) {
                close = false;
                ImGui.closeCurrentPopup();
                ImGuiHelper.endPopupModalCloseable();
                return;
            }

            FlashbackConfigV1 config = Flashback.getConfig();

            ImGuiHelper.separatorWithText(I18n.get("flashback.capture_options"));

            ImGuiHelper.inputInt(I18n.get("flashback.resolution"), config.internalExport.resolution);

            if (config.internalExport.resolution[0] < 16) config.internalExport.resolution[0] = 16;
            if (config.internalExport.resolution[1] < 16) config.internalExport.resolution[1] = 16;
            if (config.internalExport.resolution[0] % 2 != 0) config.internalExport.resolution[0] += 1;
            if (config.internalExport.resolution[1] % 2 != 0) config.internalExport.resolution[1] += 1;

            if (startEndTick[0] >= 0 && startEndTick[1] >= 0) {
                if (ImGuiHelper.inputInt(I18n.get("flashback.start_end_tick"), startEndTick)) {
                    ReplayServer replayServer = Flashback.getReplayServer();
                    if (editorState != null && replayServer != null) {
                        editorState.setExportTicks(startEndTick[0], startEndTick[1], replayServer.getTotalReplayTicks());
                    }
                }
            }
            ImGuiHelper.inputFloat(I18n.get("flashback.framerate"), config.internalExport.framerate);

            config.internalExport.projection = ImGuiHelper.enumCombo(I18n.get("flashback.projection"), config.internalExport.projection);
            if (config.internalExport.projection == ExportProjection.ORTHOGRAPHIC) {
                ImGui.sliderFloat("Ortho Zoom", config.internalExport.orthographicZoom, 0.0f, 10.0f);
            } else if (config.internalExport.projection == ExportProjection.CUBE_MAP) {
                int resX = config.internalExport.resolution[0];
                int resY = config.internalExport.resolution[1];
                if (resX % 4 != 0 || resY % 3 != 0 || resX != resY*4/3) {
                    ImGui.text("Warning: Resolution should be 4:3 for cube map export");
                }
            } else if (config.internalExport.projection == ExportProjection.EQUIRECTANGULAR) {
                int resX = config.internalExport.resolution[0];
                int resY = config.internalExport.resolution[1];
                if (resX != resY*2) {
                    ImGui.text("Warning: Resolution should be 2:1 for equirectangular export");
                }
            }

            if (ImGui.checkbox(I18n.get("flashback.reset_rng"), config.internalExport.resetRng)) {
                config.internalExport.resetRng = !config.internalExport.resetRng;
            }
            ImGuiHelper.tooltip(I18n.get("flashback.reset_rng_tooltip"));

            ImGui.sameLine();

            if (ImGui.checkbox(I18n.get("flashback.ssaa"), config.internalExport.ssaa)) {
                config.internalExport.ssaa = !config.internalExport.ssaa;
            }
            ImGuiHelper.tooltip(I18n.get("flashback.ssaa_tooltip"));

            ImGui.sameLine();

            if (ImGui.checkbox(I18n.get("flashback.no_gui"), config.internalExport.noGui)) {
                config.internalExport.noGui = !config.internalExport.noGui;
            }
            ImGuiHelper.tooltip(I18n.get("flashback.no_gui_tooltip"));

            if (ImGui.checkbox(I18n.get("flashback.depth_map"), config.internalExport.depthMap)) {
                config.internalExport.depthMap = !config.internalExport.depthMap;
            }
            ImGuiHelper.tooltip(I18n.get("flashback.depth_map_tooltip"));

            if (config.internalExport.depthMap && config.internalExport.container != VideoContainer.EXR_SEQUENCE) {
                ImGui.textWrapped("EXR Sequence is recommended for exporting depth maps. Using " + config.internalExport.container.text() + " may result in reduced precision!");
            }

            ImGuiHelper.separatorWithText(I18n.get("flashback.video_options"));

            renderVideoOptions(editorState, config);

            AudioCodec[] supportedAudioCodecs = config.internalExport.container.getSupportedAudioCodecs();
            if (supportedAudioCodecs.length > 0) {
                ImGuiHelper.separatorWithText(I18n.get("flashback.audio_options"));

                if (ImGui.checkbox(I18n.get("flashback.record_audio"), config.internalExport.recordAudio)) {
                    config.internalExport.recordAudio = !config.internalExport.recordAudio;
                }

                if (config.internalExport.recordAudio) {
                    if (ImGui.checkbox(I18n.get("flashback.stereo_audio"), config.internalExport.stereoAudio)) {
                        config.internalExport.stereoAudio = !config.internalExport.stereoAudio;
                    }

                    AudioCodec newAudioCodec = ImGuiHelper.enumCombo(I18n.get("flashback.audio_codec"), config.internalExport.audioCodec, supportedAudioCodecs);
                    if (newAudioCodec != config.internalExport.audioCodec) {
                        config.internalExport.audioCodec = newAudioCodec;
                    }

                    if (editorState != null && editorState.audioSourceEntity != null) {
                        ImGui.textUnformatted(I18n.get("flashback.audio_source.entity", editorState.audioSourceEntity));
                    } else {
                        ImGui.textUnformatted(I18n.get("flashback.audio_source.camera"));
                    }
                }
            } else {
                config.internalExport.recordAudio = false;
            }

            if (installedIncompatibleModsString != null) {
                ImGuiHelper.separatorWithText(I18n.get("flashback.incompatible_with_exporting"));
                ImGui.textWrapped(I18n.get("flashback.incompatible_with_exporting_description"));
                ImGui.pushTextWrapPos();
                ImGui.textColored(0xFF0000FF, installedIncompatibleModsString);
                ImGui.popTextWrapPos();
            }

            ImGui.dummy(0, 10 * ReplayUI.getUiScale());

            boolean isFullscreen = Minecraft.getInstance().getWindow().isExclusiveFullscreen();
            if (isFullscreen) {
                ImGui.separator();
                ImGui.textWrapped(I18n.get("flashback.export_disable_fullscreen"));
            }

            float buttonSize = (ImGui.getContentRegionAvailX() - ImGui.getStyle().getItemSpacingX()) / 2f;
            if (isFullscreen) ImGui.beginDisabled();
            if (ImGui.button(I18n.get("flashback.start_export"), buttonSize, ReplayUI.scaleUi(25)) && !isFullscreen) {
                createExportSettings(null, config).thenAccept(settings -> {
                    if (settings != null) {
                        close = true;
                        Utils.exportSequenceCount += 1;
                        Flashback.EXPORT_JOB = new ExportJob(settings);
                        config.delayedSaveToDefaultFolder();
                    }
                });
            }
            ImGui.sameLine();
            if (ImGui.button(I18n.get("flashback.add_to_queue"), buttonSize, ReplayUI.scaleUi(25)) && !isFullscreen) {
                jobName.set(I18n.get("flashback.job_n", ExportJobQueue.count()+1));
                ImGui.openPopup("QueuedJobName");
            }
            if (isFullscreen) ImGui.endDisabled();

            if (ImGuiHelper.beginPopup("QueuedJobName")) {
                ImGui.setNextItemWidth(100);
                ImGui.inputText(I18n.get("flashback.job_name"), jobName);

                if (isFullscreen) ImGui.beginDisabled();
                if ((ImGui.button(I18n.get("flashback.queue_job")) || ReplayUI.consumeConfirm()) && !isFullscreen) {
                    createExportSettings(ImGuiHelper.getString(jobName), config).thenAccept(settings -> {
                        if (settings != null) {
                            close = true;
                            Utils.exportSequenceCount += 1;
                            ExportJobQueue.queuedJobs.add(settings);
                        }
                    });
                }
                if (isFullscreen) ImGui.endDisabled();
                ImGui.sameLine();
                if (ImGui.button(I18n.get("gui.back")) || ReplayUI.consumeCancel()) {
                    ImGui.closeCurrentPopup();
                }
                ImGui.endPopup();
            }

            ImGuiHelper.endPopupModalCloseable();
        }
    }

    private static void renderVideoOptions(EditorState editorState, FlashbackConfigV1 config) {
        if (editorState != null && !editorState.replayVisuals.renderSky) {
            if (ImGui.checkbox(I18n.get("flashback.transparent_sky"), config.internalExport.transparentBackground)) {
                config.internalExport.transparentBackground = !config.internalExport.transparentBackground;
            }
            if (config.internalExport.transparentBackground && !Minecraft.getInstance().options.improvedTransparency().get()) {
                ImGui.textWrapped("It is recommended to enable 'Improved Transparency' in the Minecraft video options");
            }
        } else {
            config.internalExport.transparentBackground = false;
        }

        VideoContainer[] containers;
        if (config.internalExport.transparentBackground) {
            if (supportedContainersWithTransparency == null) {
                supportedContainersWithTransparency = VideoContainer.findSupportedContainers(true);
            }
            containers = supportedContainersWithTransparency;
        } else {
            if (supportedContainers == null) {
                supportedContainers = VideoContainer.findSupportedContainers(false);
            }
            containers = supportedContainers;
        }

        if (containers.length == 0) {
            ImGui.textUnformatted(I18n.get("flashback.no_supported_containers_found"));
            return;
        }

        if (config.internalExport.container == null || !Arrays.asList(containers).contains(config.internalExport.container)) {
            config.internalExport.container = containers[0];
        }

        config.internalExport.container = ImGuiHelper.enumCombo(I18n.get("flashback.container"), config.internalExport.container, containers);

        if (config.internalExport.container.isImageSequence()) {
            ImGui.inputText(I18n.get("flashback.filenames"), pngSequenceFormat);
            return;
        }

        VideoCodec[] codecs = config.internalExport.container.getSupportedVideoCodecs(config.internalExport.transparentBackground);
        if (codecs.length == 0) {
            ImGui.textUnformatted(I18n.get("flashback.no_supported_codecs_found"));
            return;
        }

        if (config.internalExport.videoCodec == null || !Arrays.asList(codecs).contains(config.internalExport.videoCodec)) {
            config.internalExport.videoCodec = codecs[0];
        }

        if (codecs.length > 1) {
            VideoCodec newCodec = ImGuiHelper.enumCombo(I18n.get("flashback.codec"), config.internalExport.videoCodec, codecs);
            if (newCodec != config.internalExport.videoCodec) {
                config.internalExport.videoCodec = newCodec;
                config.internalExport.selectedVideoEncoder = null;
            }
        }

        String[] encoders = config.internalExport.videoCodec.getEncoders();
        if (encoders.length > 1) {
            int encoderIndex = 0;
            for (int i = 0; i < encoders.length; i++) {
                String encoder = encoders[i];
                if (encoder.equals(config.internalExport.selectedVideoEncoder)) {
                    encoderIndex = i;
                    break;
                }
            }
            int[] encoderIndexArray = new int[]{encoderIndex};
            ImGuiHelper.combo(I18n.get("flashback.encoder"), encoderIndexArray, encoders);
            if (encoderIndexArray[0] != encoderIndex) {
                config.internalExport.selectedVideoEncoder = encoders[encoderIndexArray[0]];
            }

        }

        // ProRes exposes profile selection. Profiles, not bitrate, are what change ProRes file
        // size - the profile is a fixed data rate for a given resolution and framerate. Every other
        // codec keeps pixelDepth/proresProfile null so its selection is untouched.
        if (config.internalExport.videoCodec == VideoCodec.PRO_RES) {
            ProResProfile activeProfile = renderProResProfile(config);

            // Bit depth is not an independent ProRes setting: it follows the profile. prores_ks
            // accepts only 10-bit pixel formats (all 8-bit and 12-bit planar variants are
            // rejected), while VideoToolbox reaches 12-bit solely through 4444 XQ. So we derive
            // the depth from the profile rather than offering a second, contradictory control.
            VideoEncoder.Selection selection = activeProfile.isExplicit()
                ? VideoEncoder.selectProResWithProfile(config.internalExport.videoCodec.getEncoders(), activeProfile)
                : VideoEncoder.select(config.internalExport.videoCodec.getEncoders(), null);

            config.internalExport.pixelDepth = selection.depth();

            if (selection.isValid()) {
                ProResProfile effective = activeProfile.isExplicit()
                    ? activeProfile
                    : VideoEncoder.profileForDepth(selection.depth());

                ImGui.pushTextWrapPos();
                ImGui.textColored(0xFFAAAAAA, I18n.get("flashback.prores_format_info")
                    + " " + effective.text()
                    + "  |  " + (selection.hardware() ? I18n.get("flashback.hardware_encoder") : I18n.get("flashback.software_encoder"))
                    + (effective.isTwelveBit() ? "  |  12-bit" : "  |  10-bit"));
                ImGui.popTextWrapPos();

                if (!selection.hardware()) {
                    ImGui.pushTextWrapPos();
                    ImGui.textColored(0xFFFFAA00, I18n.get("flashback.prores_software_warning"));
                    ImGui.popTextWrapPos();
                }
            }
        } else if (config.internalExport.videoCodec == VideoCodec.H265) {
            // HEVC can genuinely encode both 8-bit and 10-bit (the hardware encoder advertises
            // nv12/yuv420p for 8-bit and p010le for Main 10), so the depth is an explicit choice
            // rather than something derived from a profile.
            config.internalExport.proresProfile = null;
            resolvePixelDepth(config, config.internalExport.videoCodec, VideoEncoder.selectableDepths(
                config.internalExport.videoCodec.getEncoders()));
        } else {
            config.internalExport.pixelDepth = null;
            config.internalExport.proresProfile = null;
        }

        if (config.internalExport.videoCodec == VideoCodec.PRO_RES) {
            ProResProfile activeProfile = renderProResProfile(config);

            // Bit depth is not an independent ProRes setting: it follows the profile. prores_ks
            // accepts only 10-bit pixel formats (all 8-bit and 12-bit planar variants are
            // rejected), while VideoToolbox reaches 12-bit solely through 4444 XQ. So we derive
            // the depth from the profile rather than offering a second, contradictory control.
            VideoEncoder.Selection selection = activeProfile.isExplicit()
                ? VideoEncoder.selectProResWithProfile(config.internalExport.videoCodec.getEncoders(), activeProfile)
                : VideoEncoder.select(config.internalExport.videoCodec.getEncoders(), null);

            config.internalExport.pixelDepth = selection.depth();

            if (selection.isValid()) {
                ProResProfile effective = activeProfile.isExplicit()
                    ? activeProfile
                    : VideoEncoder.profileForDepth(selection.depth());

                ImGui.pushTextWrapPos();
                ImGui.textColored(0xFFAAAAAA, I18n.get("flashback.prores_format_info")
                    + " " + effective.text()
                    + "  |  " + (selection.hardware() ? I18n.get("flashback.hardware_encoder") : I18n.get("flashback.software_encoder"))
                    + (effective.isTwelveBit() ? "  |  12-bit" : "  |  10-bit"));
                ImGui.popTextWrapPos();

                if (!selection.hardware()) {
                    ImGui.pushTextWrapPos();
                    ImGui.textColored(0xFFFFAA00, I18n.get("flashback.prores_software_warning"));
                    ImGui.popTextWrapPos();
                }
            }
        } else if (config.internalExport.videoCodec == VideoCodec.H265) {
            // HEVC can genuinely encode both 8-bit and 10-bit (the hardware encoder advertises
            // nv12/yuv420p for 8-bit and p010le for Main 10), so the depth is an explicit choice
            // rather than something derived from a profile.
            config.internalExport.proresProfile = null;
            resolvePixelDepth(config, config.internalExport.videoCodec, VideoEncoder.selectableDepths(
                config.internalExport.videoCodec.getEncoders()));
        } else {
            config.internalExport.pixelDepth = null;
            config.internalExport.proresProfile = null;
        }

        if (config.internalExport.videoCodec == VideoCodec.PRO_RES) {
            ProResProfile activeProfile = renderProResProfile(config);

            // Bit depth is not an independent ProRes setting: it follows the profile. prores_ks
            // accepts only 10-bit pixel formats (all 8-bit and 12-bit planar variants are
            // rejected), while VideoToolbox reaches 12-bit solely through 4444 XQ. So we derive
            // the depth from the profile rather than offering a second, contradictory control.
            VideoEncoder.Selection selection = activeProfile.isExplicit()
                ? VideoEncoder.selectProResWithProfile(config.internalExport.videoCodec.getEncoders(), activeProfile)
                : VideoEncoder.select(config.internalExport.videoCodec.getEncoders(), null);

            config.internalExport.pixelDepth = selection.depth();

            if (selection.isValid()) {
                ProResProfile effective = activeProfile.isExplicit()
                    ? activeProfile
                    : VideoEncoder.profileForDepth(selection.depth());

                ImGui.pushTextWrapPos();
                ImGui.textColored(0xFFAAAAAA, I18n.get("flashback.prores_format_info")
                    + " " + effective.text()
                    + "  |  " + (selection.hardware() ? I18n.get("flashback.hardware_encoder") : I18n.get("flashback.software_encoder"))
                    + (effective.isTwelveBit() ? "  |  12-bit" : "  |  10-bit"));
                ImGui.popTextWrapPos();

                if (!selection.hardware()) {
                    ImGui.pushTextWrapPos();
                    ImGui.textColored(0xFFFFAA00, I18n.get("flashback.prores_software_warning"));
                    ImGui.popTextWrapPos();
                }
            }
        } else if (config.internalExport.videoCodec == VideoCodec.H265) {
            // HEVC can genuinely encode both 8-bit and 10-bit (the hardware encoder advertises
            // nv12/yuv420p for 8-bit and p010le for Main 10), so the depth is an explicit choice
            // rather than something derived from a profile.
            config.internalExport.proresProfile = null;
            resolvePixelDepth(config, config.internalExport.videoCodec, VideoEncoder.selectableDepths(
                config.internalExport.videoCodec.getEncoders()));
        } else {
            config.internalExport.pixelDepth = null;
            config.internalExport.proresProfile = null;
        }

        if (config.internalExport.videoCodec == VideoCodec.PRO_RES) {
            ProResProfile activeProfile = renderProResProfile(config);

            // Bit depth is not an independent ProRes setting: it follows the profile. prores_ks
            // accepts only 10-bit pixel formats (all 8-bit and 12-bit planar variants are
            // rejected), while VideoToolbox reaches 12-bit solely through 4444 XQ. So we derive
            // the depth from the profile rather than offering a second, contradictory control.
            VideoEncoder.Selection selection = activeProfile.isExplicit()
                ? VideoEncoder.selectProResWithProfile(config.internalExport.videoCodec.getEncoders(), activeProfile)
                : VideoEncoder.select(config.internalExport.videoCodec.getEncoders(), null);

            config.internalExport.pixelDepth = selection.depth();

            if (selection.isValid()) {
                ProResProfile effective = activeProfile.isExplicit()
                    ? activeProfile
                    : VideoEncoder.profileForDepth(selection.depth());

                ImGui.pushTextWrapPos();
                ImGui.textColored(0xFFAAAAAA, I18n.get("flashback.prores_format_info")
                    + " " + effective.text()
                    + "  |  " + (selection.hardware() ? I18n.get("flashback.hardware_encoder") : I18n.get("flashback.software_encoder"))
                    + (effective.isTwelveBit() ? "  |  12-bit" : "  |  10-bit"));
                ImGui.popTextWrapPos();

                if (!selection.hardware()) {
                    ImGui.pushTextWrapPos();
                    ImGui.textColored(0xFFFFAA00, I18n.get("flashback.prores_software_warning"));
                    ImGui.popTextWrapPos();
                }
            }
        } else if (config.internalExport.videoCodec == VideoCodec.H265) {
            // HEVC can genuinely encode both 8-bit and 10-bit (the hardware encoder advertises
            // nv12/yuv420p for 8-bit and p010le for Main 10), so the depth is an explicit choice
            // rather than something derived from a profile.
            config.internalExport.proresProfile = null;
            resolvePixelDepth(config, config.internalExport.videoCodec, VideoEncoder.selectableDepths(
                config.internalExport.videoCodec.getEncoders()));
        } else {
            config.internalExport.pixelDepth = null;
            config.internalExport.proresProfile = null;
        }

        if (config.internalExport.videoCodec == VideoCodec.PRO_RES) {
            // ProRes has no bitrate and no quality knob - the profile alone sets the data rate, so
            // showing either control here would imply an effect that does not exist.
            ImGui.pushTextWrapPos();
            ImGui.textColored(0xFFAAAAAA, I18n.get("flashback.prores_bitrate_note"));
            ImGui.popTextWrapPos();
        } else if (config.internalExport.videoCodec != VideoCodec.GIF) {
            if (config.internalExport.compressionLevel == null) {
                config.internalExport.compressionLevel = CompressionLevel.BALANCED;
            }

            // One rate control, not two. Hardware encoders ignore quality/QSCALE entirely (verified:
            // global_quality yields byte-identical output at every value), so bitrate is the only
            // lever that actually works. The presets scale the automatic budget so they keep
            // adapting to resolution and framerate; "Manual" exposes the absolute bitrate instead,
            // and the two are mutually exclusive so nothing is silently overridden.
            renderRateControl(config);
        } else {
            ImGui.pushTextWrapPos();
            ImGui.textColored(0xFFFFFFFF, I18n.get("flashback.gif_output_warning"));
            ImGui.popTextWrapPos();
        }
    }

    /**
     * Resolves which output bit depths are actually deliverable, updates the config, and shows the
     * selector.
     *
     * <p>Depths are resolved through {@link VideoEncoder}, which confirms a depth by opening the
     * encoder rather than trusting its advertised pixel formats. That distinction matters here:
     * {@code prores_ks} advertises no 12-bit format at all, while {@code prores_videotoolbox}
     * reaches 12-bit only through Apple's {@code p416le}. Hardware encoders are tried first, so the
     * exported file gets the 12-bit capability that software cannot provide.
     */
    private static PixelDepth resolvePixelDepth(FlashbackConfigV1 config, VideoCodec codec, PixelDepth[] offered) {
        PixelDepth[] depths = offered;

        PixelDepth resolved;
        if (depths.length == 0) {
            resolved = null;
        } else if (config.internalExport.pixelDepth != null && Arrays.asList(depths).contains(config.internalExport.pixelDepth)) {
            resolved = config.internalExport.pixelDepth;
        } else {
            // Default to 10-bit: broadly compatible, and materially smaller than 12-bit, while
            // still giving real headroom over 8-bit.
            resolved = Arrays.asList(depths).contains(PixelDepth.BIT_10) ? PixelDepth.BIT_10 : depths[0];
        }

        config.internalExport.pixelDepth = resolved;

        if (resolved != null) {
            if (depths.length > 1) {
                resolved = ImGuiHelper.enumCombo(I18n.get("flashback.pixel_depth"), resolved, depths);
                config.internalExport.pixelDepth = resolved;
            } else {
                ImGuiHelper.combo(I18n.get("flashback.pixel_depth"), new int[]{0}, new String[]{resolved.text()});
            }
        }

        return resolved;
    }

    /**
     * Shows the ProRes profile picker. Only encoders that honour the profile option get the full
     * list; the software encoder ignores it entirely, so it is limited to the two profiles it can
     * actually reach through its pixel format.
     */
    private static ProResProfile renderProResProfile(FlashbackConfigV1 config) {
        String[] candidates = config.internalExport.videoCodec.getEncoders();

        boolean hardwareAvailable = false;
        for (String encoder : candidates) {
            if (VideoEncoder.isHardwareEncoder(encoder)) {
                hardwareAvailable = true;
                break;
            }
        }

        ProResProfile current = config.internalExport.proresProfile;
        if (current == null) {
            current = ProResProfile.AUTO;
        }
        if (!current.isExplicit() && !hardwareAvailable) {
            // Nothing that honours profiles; let the label describe the derived profile instead.
            current = ProResProfile.HQ;
        }

        ProResProfile selected = ImGuiHelper.enumCombo(I18n.get("flashback.prores_profile"), current,
            ProResProfile.menuEntries(hardwareAvailable));

        if (selected == null) {
            selected = ProResProfile.AUTO;
        }

        config.internalExport.proresProfile = selected.isExplicit() ? selected : null;

        if (selected.isExplicit()) {
            ImGui.pushTextWrapPos();
            ImGui.textColored(0xFFAAAAAA, I18n.get("flashback.prores_profile_datareate") + " "
                + Math.round(selected.relativeDataRate() * 100) + "%");
            ImGui.popTextWrapPos();
        }

        return selected;
    }

    /** The "Manual" entry appended to the quality presets, since it is not a scale factor. */
    private static final String MANUAL_BITRATE_ENTRY = "Manual";

    /** True when the user chose to type an absolute bitrate rather than pick a preset. */
    private static boolean isManualBitrate(FlashbackConfigV1 config) {
        return config.internalExport.useMaximumBitrate;
    }

    /**
     * Draws the single rate control: a quality preset list (with a Manual entry) or, when Manual is
     * chosen, the bitrate field. The preset is a scale on the automatic budget; Manual is an
     * absolute value. Only one is ever live.
     */
    private static void renderRateControl(FlashbackConfigV1 config) {
        boolean manual = isManualBitrate(config);

        String[] entries = new String[CompressionLevel.values().length + 1];
        for (int i = 0; i < CompressionLevel.values().length; i++) {
            entries[i] = CompressionLevel.values()[i].text();
        }
        entries[entries.length - 1] = MANUAL_BITRATE_ENTRY;

        int currentIndex = manual ? entries.length - 1 : config.internalExport.compressionLevel.ordinal();
        int[] index = new int[]{currentIndex};
        ImGuiHelper.combo(I18n.get("flashback.compression_level"), index, entries);

        if (index[0] != currentIndex) {
            if (index[0] == entries.length - 1) {
                config.internalExport.useMaximumBitrate = true;
            } else {
                config.internalExport.useMaximumBitrate = false;
                config.internalExport.compressionLevel = CompressionLevel.values()[index[0]];
            }
        }

        if (config.internalExport.useMaximumBitrate) {
            ImGui.inputText(I18n.get("flashback.bitrate"), bitrate);
            if (ImGui.isItemDeactivatedAfterEdit()) {
                int parsed = stringToBitrate(ImGuiHelper.getString(bitrate));
                if (parsed > 0) {
                    bitrate.set(bitrateToString(parsed));
                }
            }
        } else {
            int[] resolution = config.internalExport.resolution;
            long budget = automaticBitrateBudget(resolution[0], resolution[1],
                Math.max(1f, config.internalExport.framerate[0]));
            long effective = Math.max(1_000_000L,
                Math.round(budget * config.internalExport.compressionLevel.bitrateRatio()));
            ImGui.pushTextWrapPos();
            ImGui.textColored(0xFFAAAAAA, I18n.get("flashback.effective_bitrate") + " "
                + bitrateToString((int) effective));
            ImGui.popTextWrapPos();
        }
    }

    /**
     * Mirrors the encoder's automatic bitrate budget: 8 bits per pixel per second, capped at
     * 288 Mbps (the libopenh264 ceiling). Shown to the user so a "quality" preset has a visible
     * meaning in Mbps rather than being an opaque multiplier.
     */
    static long automaticBitrateBudget(int width, int height, double framerate) {
        long perPixel = (long) width * height * 8L;
        return Math.min(288_000_000L, 4096L + (long) (perPixel * framerate));
    }

    private static CompletableFuture<ExportSettings> createExportSettings(@Nullable String name, FlashbackConfigV1 config) {
        String defaultName = getDefaultFilename(name, config.internalExport.container.extension(), config);

        Function<String, ExportSettings> callback = pathStr -> {
            if (pathStr != null) {
                EditorState editorState = EditorStateManager.getCurrent();
                if (editorState == null) {
                    return null;
                }

                int start, end;
                if (startEndTick[0] >= 0 && startEndTick[1] >= 0) {
                    start = Math.max(0, startEndTick[0]);
                    end = Math.max(start, startEndTick[1]);
                } else {
                    var firstAndLastInTracks = editorState.getFirstAndLastTicksInTracks();
                    start = firstAndLastInTracks.start();
                    end = firstAndLastInTracks.end();

                    if (start < 0) {
                        start = 0;
                    }
                    if (end < 0 || start == end) {
                        ReplayServer replayServer = Flashback.getReplayServer();
                        if (replayServer != null) {
                            end = replayServer.getTotalReplayTicks();
                        } else {
                            end = start+100;
                        }
                    }
                }

                ReplayServer replayServer = Flashback.getReplayServer();
                if (replayServer != null) {
                    int totalTicks = replayServer.getTotalReplayTicks();
                    start = Math.min(start, totalTicks);
                    end = Math.min(end, totalTicks);
                }

                LocalPlayer player = Minecraft.getInstance().player;
                if (player == null) {
                    return null;
                }

                boolean transparent = config.internalExport.transparentBackground && !editorState.replayVisuals.renderSky;

                VideoCodec useVideoCodec = config.internalExport.videoCodec;
                VideoCodec[] codecs = config.internalExport.container.getSupportedVideoCodecs(transparent);
                if (useVideoCodec == null || !Arrays.asList(codecs).contains(useVideoCodec)) {
                    useVideoCodec = codecs[0];
                }
                boolean usePixelDepth = useVideoCodec == VideoCodec.PRO_RES || useVideoCodec == VideoCodec.H265;
                PixelDepth depth = usePixelDepth ? config.internalExport.pixelDepth : null;

                // Quality is resolved here, once, into the single bitrate value the writer uses.
                // An explicit bitrate in the config still wins if one was preserved.
                boolean explicitBitrate = config.internalExport.useMaximumBitrate;
                int resolvedBitrate;
                if (explicitBitrate) {
                    resolvedBitrate = stringToBitrate(ImGuiHelper.getString(bitrate));
                    if (resolvedBitrate <= 0) {
                        resolvedBitrate = 0;
                    }
                } else {
                    CompressionLevel level = config.internalExport.compressionLevel != null
                        ? config.internalExport.compressionLevel
                        : CompressionLevel.BALANCED;
                    long budget = automaticBitrateBudget(config.internalExport.resolution[0],
                        config.internalExport.resolution[1], Math.max(1f, config.internalExport.framerate[0]));
                    resolvedBitrate = (int) Math.max(1_000_000L, Math.round(budget * level.bitrateRatio()));
                }

                ProResProfile profile = usePixelDepth ? config.internalExport.proresProfile : null;
                boolean explicitProfile = profile != null && profile.isExplicit();

                // ProRes: the profile decides format and depth. HEVC: the user's explicit 8/10-bit
                // choice must be honoured exactly, so use the depth-exact selection and only fall
                // back to the generic path if nothing can provide it.
                VideoEncoder.Selection selection;
                if (explicitProfile) {
                    selection = VideoEncoder.selectProResWithProfile(useVideoCodec.getEncoders(), profile);
                } else if (useVideoCodec == VideoCodec.H265 && depth != null) {
                    selection = VideoEncoder.selectWithDepth(useVideoCodec.getEncoders(), depth);
                    if (!selection.isValid()) {
                        selection = VideoEncoder.select(useVideoCodec.getEncoders(), depth);
                    }
                } else {
                    selection = VideoEncoder.select(useVideoCodec.getEncoders(), depth);
                }

                String encoder = selection.isValid()
                    ? selection.encoder()
                    : getSelectedEncoderForCodecWithDepth(config, useVideoCodec, depth);
                String pixelFormatName = usePixelDepth && selection.isValid() ? selection.pixelFormat() : null;

                AudioCodec useAudioCodec = config.internalExport.audioCodec;
                if (!config.internalExport.recordAudio || config.internalExport.container.getSupportedAudioCodecs().length == 0) {
                    useAudioCodec = null;
                }

                Path path = Path.of(pathStr);
                config.internalExport.defaultExportPath = path.getParent().toString();
                return new ExportSettings(name, editorState.copy(),
                    player.position(), player.getYRot(), player.getXRot(),
                    config.internalExport.resolution[0], config.internalExport.resolution[1], start, end,
                    config.internalExport.projection, config.internalExport.orthographicZoom[0],
                    Math.max(1, config.internalExport.framerate[0]), config.internalExport.resetRng, config.internalExport.depthMap,
                    config.internalExport.container, useVideoCodec, encoder, depth, pixelFormatName,
                    explicitProfile ? profile : null,
                    resolvedBitrate,
                    transparent, config.internalExport.ssaa, config.internalExport.noGui,
                    config.internalExport.stereoAudio, useAudioCodec,
                    path, ImGuiHelper.getString(pngSequenceFormat));
            }

            return null;
        };

        String defaultExportPathString = config.internalExport.defaultExportPath;
        if (config.internalExport.container.isImageSequence()) {
            return AsyncFileDialogs.openFolderDialog(defaultExportPathString).thenApply(callback);
        } else {
            return AsyncFileDialogs.saveFileDialog(defaultExportPathString, defaultName,
                config.internalExport.container.extension(), config.internalExport.container.extension()).thenApply(callback);
        }

    }

    /**
     * Resolves the encoder actually used for a codec, preferring hardware acceleration but only
     * where it can genuinely deliver the requested depth. Returns null when the codec has no
     * usable encoder.
     */
    private static String getSelectedEncoderForCodecWithDepth(FlashbackConfigV1 config, VideoCodec useVideoCodec, @Nullable PixelDepth depth) {
        String[] validEncoders = useVideoCodec.getEncoders();
        if (validEncoders == null || validEncoders.length == 0) {
            return null;
        }

        VideoEncoder.Selection selection = VideoEncoder.select(validEncoders, depth);
        if (selection.isValid()) {
            return selection.encoder();
        }

        return validEncoders[0];
    }

    public static @NotNull String getDefaultFilename(@Nullable String name, String extension, FlashbackConfigV1 config) {
        Path defaultPath = FabricLoader.getInstance().getGameDir();

        try {
            if (config.internalExport.defaultExportPath == null || config.internalExport.defaultExportPath.isBlank() || !Files.exists(Path.of(config.internalExport.defaultExportPath))) {
                config.internalExport.defaultExportPath = defaultPath.toString();
            } else {
                defaultPath = Path.of(config.internalExport.defaultExportPath);
            }
        } catch (Exception ignored) {}

        String defaultName = null;
        if (name != null) {
            try {
                defaultName = FileUtil.findAvailableName(defaultPath, name, "." + extension);
            } catch (Exception ignored) {}
        }
        if (defaultName == null) {
            String desiredName = Utils.resolveFilenameTemplate(Flashback.getConfig().exporting.defaultExportFilename);
            try {
                defaultName = FileUtil.findAvailableName(defaultPath, desiredName, "." + extension);
            } catch (Exception ignored) {}
        }
        if (defaultName == null) {
            String desiredName = Utils.resolveFilenameTemplate(Flashback.getConfig().exporting.defaultExportFilename);
            defaultName = desiredName + "." + extension;
        }
        return defaultName;
    }

    private static int stringToBitrate(String string) {
        int number = 0;
        int modifier = 1;
        int total = 0;

        for (char c : string.toCharArray()) {
            if (c >= '0' && c <= '9') {
                if (modifier > 1) {
                    total += number * modifier;
                    number = 0;
                    modifier = 1;
                }

                number *= 10;
                number += c - '0';
            } else if (c == 'k') {
                modifier *= 1000;
            } else if (c == 'm') {
                modifier *= 1000000;
            } else if (c == 'b') {
                modifier *= 1000000000;
            }
        }

        total += number * modifier;

        return total;
    }

    private static String bitrateToString(int bitrate) {
        if (bitrate >= 1000000000 && bitrate == (bitrate/1000000000)*1000000000) {
            return (bitrate/1000000000) + "b";
        } else if (bitrate >= 1000000 && bitrate == (bitrate/1000000)*1000000) {
            return (bitrate/1000000) + "m";
        } else if (bitrate >= 1000 && bitrate == (bitrate/1000)*1000) {
            return (bitrate/1000) + "k";
        } else {
            return String.valueOf(bitrate);
        }
    }

    public static void open() {
        open = true;

        EditorState editorState = EditorStateManager.getCurrent();
        if (editorState == null) {
            startEndTick[0] = -1;
            startEndTick[1] = -1;
            return;
        }

        var startAndEnd = editorState.getExportStartAndEnd();
        startEndTick[0] = startAndEnd.start();
        startEndTick[1] = startAndEnd.end();
    }

}
