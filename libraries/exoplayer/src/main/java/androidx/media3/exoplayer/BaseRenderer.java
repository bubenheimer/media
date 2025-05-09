/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.exoplayer;

import static androidx.media3.common.util.Assertions.checkNotNull;
import static java.lang.Math.max;

import androidx.annotation.GuardedBy;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Timeline;
import androidx.media3.common.util.Assertions;
import androidx.media3.common.util.Clock;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.decoder.DecoderInputBuffer.InsufficientCapacityException;
import androidx.media3.exoplayer.analytics.PlayerId;
import androidx.media3.exoplayer.source.MediaPeriod;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.SampleStream;
import androidx.media3.exoplayer.source.SampleStream.ReadDataResult;
import androidx.media3.exoplayer.source.SampleStream.ReadFlags;
import java.io.IOException;
import java.util.Objects;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * An abstract base class suitable for most {@link Renderer} implementations.
 *
 * <p>It converts many of the state transitions explained in {@link Renderer} docs to protected
 * callbacks and provides utilities to access current state values without tracking them manually:
 *
 * <ul>
 *   <li>{@link #onInit}, {@link #onEnabled}, {@link #onStarted}, {@link #onStopped}, {@link
 *       #onDisabled}, {@link #onReset} and {@link #onRelease} are called for the corresponding
 *       {@link Renderer} method.
 *   <li>{@link #onStreamChanged} is called for both the initial stream set via {@link #enable} as
 *       well as subsequent streams set via {@link #replaceStream}.
 *   <li>{@link #onPositionReset} is called for the initial reset via {@link #enable} as well as
 *       subsequent resets via {@link #resetPosition}.
 *   <li>The current {@link SampleStream} can be read with {@link #readSource} or skipped with
 *       {@link #skipSource}. {@link #isSourceReady()} returning {@code true} indicates that samples
 *       are available to be read.
 *   <li>Current state is available with additional getter methods like {@link
 *       #getLastResetPositionUs()}, {@link #getPlayerId()}, {@link #getTimeline()}.
 *   <li>Exceptions can be created with {@link #createRendererException} to fill in additional
 *       metadata about the renderer automatically.
 *   <li>The renderer can call {@link #onRendererCapabilitiesChanged()} to tell the player of a
 *       change in its capabilities, which may lead to new tracks being selected for playback.
 * </ul>
 */
@UnstableApi
public abstract class BaseRenderer implements Renderer, RendererCapabilities {

  private final Object lock;
  private final @C.TrackType int trackType;
  private final FormatHolder formatHolder;

  @Nullable private RendererConfiguration configuration;
  private int index;
  private @MonotonicNonNull PlayerId playerId;
  private @MonotonicNonNull Clock clock;
  private int state;
  @Nullable private SampleStream stream;
  @Nullable private Format[] streamFormats;
  private long streamOffsetUs;
  private long lastResetPositionUs;
  private long readingPositionUs;
  private boolean streamIsFinal;
  private boolean throwRendererExceptionIsExecuting;
  private Timeline timeline;

  @GuardedBy("lock")
  @Nullable
  private RendererCapabilities.Listener rendererCapabilitiesListener;

  /**
   * @param trackType The track type that the renderer handles. One of the {@link C} {@code
   *     TRACK_TYPE_*} constants.
   */
  public BaseRenderer(@C.TrackType int trackType) {
    lock = new Object();
    this.trackType = trackType;
    formatHolder = new FormatHolder();
    readingPositionUs = C.TIME_END_OF_SOURCE;
    timeline = Timeline.EMPTY;
  }

  @Override
  public final @C.TrackType int getTrackType() {
    return trackType;
  }

  @Override
  public final RendererCapabilities getCapabilities() {
    return this;
  }

  @Override
  public final void init(int index, PlayerId playerId, Clock clock) {
    this.index = index;
    this.playerId = playerId;
    this.clock = clock;
    onInit();
  }

  @Override
  @Nullable
  public MediaClock getMediaClock() {
    return null;
  }

  @Override
  public final int getState() {
    return state;
  }

  @Override
  public final void enable(
      RendererConfiguration configuration,
      Format[] formats,
      SampleStream stream,
      long positionUs,
      boolean joining,
      boolean mayRenderStartOfStream,
      long startPositionUs,
      long offsetUs,
      MediaSource.MediaPeriodId mediaPeriodId)
      throws ExoPlaybackException {
    Assertions.checkState(state == STATE_DISABLED);
    this.configuration = configuration;
    state = STATE_ENABLED;
    onEnabled(joining, mayRenderStartOfStream);
    replaceStream(formats, stream, startPositionUs, offsetUs, mediaPeriodId);
    resetPosition(startPositionUs, joining);
  }

  @Override
  public final void start() throws ExoPlaybackException {
    Assertions.checkState(state == STATE_ENABLED);
    state = STATE_STARTED;
    onStarted();
  }

  @Override
  public final void replaceStream(
      Format[] formats,
      SampleStream stream,
      long startPositionUs,
      long offsetUs,
      MediaSource.MediaPeriodId mediaPeriodId)
      throws ExoPlaybackException {
    Assertions.checkState(!streamIsFinal);
    this.stream = stream;
    if (readingPositionUs == C.TIME_END_OF_SOURCE) {
      readingPositionUs = startPositionUs;
    }
    streamFormats = formats;
    streamOffsetUs = offsetUs;
    onStreamChanged(formats, startPositionUs, offsetUs, mediaPeriodId);
  }

  @Override
  @Nullable
  public final SampleStream getStream() {
    return stream;
  }

  @Override
  public final boolean hasReadStreamToEnd() {
    return readingPositionUs == C.TIME_END_OF_SOURCE;
  }

  @Override
  public final long getReadingPositionUs() {
    return readingPositionUs;
  }

  @Override
  public final void setCurrentStreamFinal() {
    streamIsFinal = true;
  }

  @Override
  public final boolean isCurrentStreamFinal() {
    return streamIsFinal;
  }

  @Override
  public final void maybeThrowStreamError() throws IOException {
    Assertions.checkNotNull(stream).maybeThrowError();
  }

  @Override
  public final void setTimeline(Timeline timeline) {
    if (!Objects.equals(this.timeline, timeline)) {
      this.timeline = timeline;
      onTimelineChanged(this.timeline);
    }
  }

  @Override
  public final void resetPosition(long positionUs) throws ExoPlaybackException {
    resetPosition(positionUs, /* joining= */ false);
  }

  private void resetPosition(long positionUs, boolean joining) throws ExoPlaybackException {
    streamIsFinal = false;
    lastResetPositionUs = positionUs;
    readingPositionUs = positionUs;
    onPositionReset(positionUs, joining);
  }

  @Override
  public final void stop() {
    Assertions.checkState(state == STATE_STARTED);
    state = STATE_ENABLED;
    onStopped();
  }

  @Override
  public final void disable() {
    Assertions.checkState(state == STATE_ENABLED);
    formatHolder.clear();
    state = STATE_DISABLED;
    stream = null;
    streamFormats = null;
    streamIsFinal = false;
    onDisabled();
  }

  @Override
  public final void reset() {
    Assertions.checkState(state == STATE_DISABLED);
    formatHolder.clear();
    onReset();
  }

  @Override
  public final void release() {
    Assertions.checkState(state == STATE_DISABLED);
    onRelease();
  }

  // RendererCapabilities implementation.

  @Override
  public @AdaptiveSupport int supportsMixedMimeTypeAdaptation() throws ExoPlaybackException {
    return ADAPTIVE_NOT_SUPPORTED;
  }

  @Override
  public final void setListener(RendererCapabilities.Listener listener) {
    synchronized (lock) {
      this.rendererCapabilitiesListener = listener;
    }
  }

  @Override
  public final void clearListener() {
    synchronized (lock) {
      this.rendererCapabilitiesListener = null;
    }
  }

  // PlayerMessage.Target implementation.

  @Override
  public void handleMessage(@MessageType int messageType, @Nullable Object message)
      throws ExoPlaybackException {
    // Do nothing.
  }

  // Methods to be overridden by subclasses.

  /** Called when the renderer is initialized. */
  protected void onInit() {
    // Do nothing
  }

  /**
   * Called when the renderer is enabled.
   *
   * <p>The default implementation is a no-op.
   *
   * @param joining Whether this renderer is being enabled to join an ongoing playback.
   * @param mayRenderStartOfStream Whether this renderer is allowed to render the start of the
   *     stream even if the state is not {@link #STATE_STARTED} yet.
   * @throws ExoPlaybackException If an error occurs.
   */
  protected void onEnabled(boolean joining, boolean mayRenderStartOfStream)
      throws ExoPlaybackException {
    // Do nothing.
  }

  /**
   * Called when the renderer's stream has changed. This occurs when the renderer is enabled after
   * {@link #onEnabled(boolean, boolean)} has been called, and also when the stream has been
   * replaced whilst the renderer is enabled or started.
   *
   * <p>The default implementation is a no-op.
   *
   * @param formats The enabled formats.
   * @param startPositionUs The start position of the new stream in renderer time (microseconds).
   * @param offsetUs The offset that will be added to the timestamps of buffers read via {@link
   *     #readSource} so that decoder input buffers have monotonically increasing timestamps.
   * @param mediaPeriodId The {@link MediaSource.MediaPeriodId} of the {@link MediaPeriod} that
   *     produces the stream.
   * @throws ExoPlaybackException If an error occurs.
   */
  protected void onStreamChanged(
      Format[] formats,
      long startPositionUs,
      long offsetUs,
      MediaSource.MediaPeriodId mediaPeriodId)
      throws ExoPlaybackException {
    // Do nothing.
  }

  /**
   * Called when the position is reset. This occurs when the renderer is enabled after {@link
   * #onStreamChanged(Format[], long, long, MediaSource.MediaPeriodId)} has been called, and also
   * when a position discontinuity is encountered.
   *
   * <p>After a position reset, the renderer's {@link SampleStream} is guaranteed to provide samples
   * starting from a key frame.
   *
   * <p>The default implementation is a no-op.
   *
   * @param positionUs The new playback position in microseconds.
   * @param joining Whether this renderer is being enabled to join an ongoing playback.
   * @throws ExoPlaybackException If an error occurs.
   */
  protected void onPositionReset(long positionUs, boolean joining) throws ExoPlaybackException {
    // Do nothing.
  }

  /**
   * Called when the renderer is started.
   *
   * <p>The default implementation is a no-op.
   *
   * @throws ExoPlaybackException If an error occurs.
   */
  protected void onStarted() throws ExoPlaybackException {
    // Do nothing.
  }

  /**
   * Called when the renderer is stopped.
   *
   * <p>The default implementation is a no-op.
   */
  protected void onStopped() {
    // Do nothing.
  }

  /**
   * Called when the renderer is disabled.
   *
   * <p>The default implementation is a no-op.
   */
  protected void onDisabled() {
    // Do nothing.
  }

  /**
   * Called when the renderer is reset.
   *
   * <p>The default implementation is a no-op.
   */
  protected void onReset() {
    // Do nothing.
  }

  /**
   * Called when the renderer is released.
   *
   * <p>The default implementation is a no-op.
   */
  protected void onRelease() {
    // Do nothing.
  }

  /**
   * Called when a new timeline is {@linkplain #setTimeline(Timeline) set}.
   *
   * <p>The default implementation is a no-op.
   *
   * @param timeline The new timeline, which can also be obtained from {@link #getTimeline()}.
   */
  protected void onTimelineChanged(Timeline timeline) {
    // Do nothing
  }

  // Methods to be called by subclasses.

  /**
   * Returns the position passed to the most recent call to {@link #enable} or {@link
   * #resetPosition}.
   */
  protected final long getLastResetPositionUs() {
    return lastResetPositionUs;
  }

  /**
   * Returns the offset added to timestamps of buffers read from the {@link SampleStream}.
   *
   * <p>Must only be called if the renderer is at least {@link #STATE_ENABLED}.
   */
  protected final long getStreamOffsetUs() {
    return streamOffsetUs;
  }

  /** Returns a clear {@link FormatHolder}. */
  protected final FormatHolder getFormatHolder() {
    formatHolder.clear();
    return formatHolder;
  }

  /**
   * Returns the formats of the currently enabled stream.
   *
   * <p>This method may be called when the renderer is in the following states: {@link
   * #STATE_ENABLED}, {@link #STATE_STARTED}.
   */
  protected final Format[] getStreamFormats() {
    return Assertions.checkNotNull(streamFormats);
  }

  /**
   * Returns the configuration set when the renderer was most recently enabled.
   *
   * <p>This method may be called when the renderer is in the following states: {@link
   * #STATE_ENABLED}, {@link #STATE_STARTED}.
   */
  protected final RendererConfiguration getConfiguration() {
    return Assertions.checkNotNull(configuration);
  }

  /**
   * Returns the index of the renderer within the player.
   *
   * <p>Must only be used after the renderer has been initialized by the player.
   */
  protected final int getIndex() {
    return index;
  }

  /**
   * Returns the {@link PlayerId} of the player using this renderer.
   *
   * <p>Must only be used after the renderer has been initialized by the player.
   */
  protected final PlayerId getPlayerId() {
    return checkNotNull(playerId);
  }

  /**
   * Returns the {@link Clock}.
   *
   * <p>Must only be used after the renderer has been initialized by the player.
   */
  protected final Clock getClock() {
    return checkNotNull(clock);
  }

  /** Returns the current {@link Timeline} containing the rendered stream. */
  protected final Timeline getTimeline() {
    return timeline;
  }

  /**
   * Creates an {@link ExoPlaybackException} of type {@link ExoPlaybackException#TYPE_RENDERER} for
   * this renderer.
   *
   * @param cause The cause of the exception.
   * @param format The current format used by the renderer. May be null.
   * @param errorCode A {@link PlaybackException.ErrorCode} to identify the cause of the playback
   *     failure.
   * @return The created instance, in which {@link ExoPlaybackException#isRecoverable} is {@code
   *     false}.
   */
  protected final ExoPlaybackException createRendererException(
      Throwable cause, @Nullable Format format, @PlaybackException.ErrorCode int errorCode) {
    return createRendererException(cause, format, /* isRecoverable= */ false, errorCode);
  }

  /**
   * Creates an {@link ExoPlaybackException} of type {@link ExoPlaybackException#TYPE_RENDERER} for
   * this renderer.
   *
   * @param cause The cause of the exception.
   * @param format The current format used by the renderer. May be null.
   * @param isRecoverable If the error is recoverable by disabling and re-enabling the renderer.
   * @param errorCode A {@link PlaybackException.ErrorCode} to identify the cause of the playback
   *     failure.
   * @return The created instance.
   */
  protected final ExoPlaybackException createRendererException(
      Throwable cause,
      @Nullable Format format,
      boolean isRecoverable,
      @PlaybackException.ErrorCode int errorCode) {
    @C.FormatSupport int formatSupport = C.FORMAT_HANDLED;
    if (format != null && !throwRendererExceptionIsExecuting) {
      // Prevent recursive re-entry from subclass supportsFormat implementations.
      throwRendererExceptionIsExecuting = true;
      try {
        formatSupport = RendererCapabilities.getFormatSupport(supportsFormat(format));
      } catch (ExoPlaybackException e) {
        // Ignore, we are already failing.
      } finally {
        throwRendererExceptionIsExecuting = false;
      }
    }
    return ExoPlaybackException.createForRenderer(
        cause, getName(), getIndex(), format, formatSupport, isRecoverable, errorCode);
  }

  /**
   * Reads from the enabled upstream source. If the upstream source has been read to the end then
   * {@link C#RESULT_BUFFER_READ} is only returned if {@link #setCurrentStreamFinal()} has been
   * called. {@link C#RESULT_NOTHING_READ} is returned otherwise.
   *
   * <p>This method may be called when the renderer is in the following states: {@link
   * #STATE_ENABLED}, {@link #STATE_STARTED}.
   *
   * @param formatHolder A {@link FormatHolder} to populate in the case of reading a format.
   * @param buffer A {@link DecoderInputBuffer} to populate in the case of reading a sample or the
   *     end of the stream. If the end of the stream has been reached, the {@link
   *     C#BUFFER_FLAG_END_OF_STREAM} flag will be set on the buffer.
   * @param readFlags Flags controlling the behavior of this read operation.
   * @return The {@link ReadDataResult result} of the read operation.
   * @throws InsufficientCapacityException If the {@code buffer} has insufficient capacity to hold
   *     the data of a sample being read. The buffer {@link DecoderInputBuffer#timeUs timestamp} and
   *     flags are populated if this exception is thrown, but the read position is not advanced.
   */
  protected final @ReadDataResult int readSource(
      FormatHolder formatHolder, DecoderInputBuffer buffer, @ReadFlags int readFlags) {
    @ReadDataResult
    int result = Assertions.checkNotNull(stream).readData(formatHolder, buffer, readFlags);
    if (result == C.RESULT_BUFFER_READ) {
      if (buffer.isEndOfStream()) {
        readingPositionUs = C.TIME_END_OF_SOURCE;
        return streamIsFinal ? C.RESULT_BUFFER_READ : C.RESULT_NOTHING_READ;
      }
      buffer.timeUs += streamOffsetUs;
      readingPositionUs = max(readingPositionUs, buffer.timeUs);
    } else if (result == C.RESULT_FORMAT_READ) {
      Format format = Assertions.checkNotNull(formatHolder.format);
      if (format.subsampleOffsetUs != Format.OFFSET_SAMPLE_RELATIVE) {
        format =
            format
                .buildUpon()
                .setSubsampleOffsetUs(format.subsampleOffsetUs + streamOffsetUs)
                .build();
        formatHolder.format = format;
      }
    }
    return result;
  }

  /**
   * Attempts to skip to the keyframe before the specified position, or to the end of the stream if
   * {@code positionUs} is beyond it.
   *
   * <p>This method may be called when the renderer is in the following states: {@link
   * #STATE_ENABLED}, {@link #STATE_STARTED}.
   *
   * @param positionUs The position in microseconds.
   * @return The number of samples that were skipped.
   */
  protected int skipSource(long positionUs) {
    return Assertions.checkNotNull(stream).skipData(positionUs - streamOffsetUs);
  }

  /**
   * Returns whether the upstream source is ready.
   *
   * <p>This method may be called when the renderer is in the following states: {@link
   * #STATE_ENABLED}, {@link #STATE_STARTED}.
   */
  protected final boolean isSourceReady() {
    return hasReadStreamToEnd() ? streamIsFinal : Assertions.checkNotNull(stream).isReady();
  }

  /** Called when the renderer capabilities are changed. */
  protected final void onRendererCapabilitiesChanged() {
    @Nullable RendererCapabilities.Listener listener;
    synchronized (lock) {
      listener = rendererCapabilitiesListener;
    }
    if (listener != null) {
      listener.onRendererCapabilitiesChanged(this);
    }
  }
}
