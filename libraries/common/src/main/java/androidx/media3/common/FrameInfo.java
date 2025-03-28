/*
 * Copyright 2022 The Android Open Source Project
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
package androidx.media3.common;

import static androidx.media3.common.util.Assertions.checkArgument;

import androidx.media3.common.util.UnstableApi;

/** Value class specifying information about a decoded video frame. */
@UnstableApi
public class FrameInfo {

  /**
   * The {@link Format} of the frame.
   *
   * <p>The {@link Format#colorInfo} must be set, and the {@link Format#width} and {@link
   * Format#height} must be greater than 0.
   */
  public final Format format;

  /** The offset that must be added to the frame presentation timestamp, in microseconds. */
  public final long offsetToAddUs;

  /**
   * Creates an instance.
   *
   * @param format See {@link #format}.
   * @param offsetToAddUs See {@link #offsetToAddUs}.
   */
  public FrameInfo(Format format, long offsetToAddUs) {
    checkArgument(format.colorInfo != null, "format colorInfo must be set");
    checkArgument(format.width > 0, "format width must be positive, but is: " + format.width);
    checkArgument(format.height > 0, "format height must be positive, but is: " + format.height);

    this.format = format;
    this.offsetToAddUs = offsetToAddUs;
  }
}
