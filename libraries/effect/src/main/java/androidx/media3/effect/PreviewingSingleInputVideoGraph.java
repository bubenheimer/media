/*
 * Copyright 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.media3.effect;

import android.content.Context;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.DebugViewProvider;
import androidx.media3.common.Effect;
import androidx.media3.common.PreviewingVideoGraph;
import androidx.media3.common.VideoCompositorSettings;
import androidx.media3.common.VideoFrameProcessor;
import androidx.media3.common.util.UnstableApi;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * A {@link PreviewingVideoGraph Previewing} specific implementation of {@link
 * SingleInputVideoGraph}.
 */
@UnstableApi
public final class PreviewingSingleInputVideoGraph extends SingleInputVideoGraph
    implements PreviewingVideoGraph {

  /** A factory for creating a {@link PreviewingSingleInputVideoGraph}. */
  public static final class Factory implements PreviewingVideoGraph.Factory {

    private final VideoFrameProcessor.Factory videoFrameProcessorFactory;

    /**
     * Creates a new factory that uses the {@link DefaultVideoFrameProcessor.Factory} with its
     * default values.
     */
    public Factory() {
      this(new DefaultVideoFrameProcessor.Factory.Builder().build());
    }

    /**
     * Creates an instance that uses the supplied {@code videoFrameProcessorFactory} to create
     * {@link VideoFrameProcessor} instances.
     */
    public Factory(VideoFrameProcessor.Factory videoFrameProcessorFactory) {
      this.videoFrameProcessorFactory = videoFrameProcessorFactory;
    }

    @Override
    public PreviewingVideoGraph create(
        Context context,
        ColorInfo outputColorInfo,
        DebugViewProvider debugViewProvider,
        Listener listener,
        Executor listenerExecutor,
        VideoCompositorSettings videoCompositorSettings,
        List<Effect> compositionEffects,
        long initialTimestampOffsetUs) {
      return new PreviewingSingleInputVideoGraph(
          context,
          videoFrameProcessorFactory,
          outputColorInfo,
          debugViewProvider,
          listener,
          listenerExecutor,
          initialTimestampOffsetUs);
    }

    @Override
    public boolean supportsMultipleInputs() {
      return false;
    }
  }

  private PreviewingSingleInputVideoGraph(
      Context context,
      VideoFrameProcessor.Factory videoFrameProcessorFactory,
      ColorInfo outputColorInfo,
      DebugViewProvider debugViewProvider,
      Listener listener,
      Executor listenerExecutor,
      long initialTimestampOffsetUs) {
    super(
        context,
        videoFrameProcessorFactory,
        outputColorInfo,
        listener,
        debugViewProvider,
        listenerExecutor,
        VideoCompositorSettings.DEFAULT,
        // Previewing needs frame render timing.
        /* renderFramesAutomatically= */ false,
        initialTimestampOffsetUs);
  }

  @Override
  public void renderOutputFrame(long renderTimeNs) {
    getProcessor(getInputIndex()).renderOutputFrame(renderTimeNs);
  }
}
