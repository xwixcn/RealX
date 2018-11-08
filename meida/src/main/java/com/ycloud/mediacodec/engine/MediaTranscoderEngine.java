/*
 * Copyright (C) 2014 Yuya Tanaka
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.ycloud.mediacodec.engine;

import android.annotation.TargetApi;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.os.Build;
import android.util.Log;

import java.io.FileDescriptor;
import java.io.IOException;

import com.ycloud.mediacodec.format.IMediaFormatStrategy;
import com.ycloud.mediacodec.utils.MediaExtractorUtils;
import com.ycloud.mediacodec.InvalidOutputFormatException;

/**
 * Internal engine, do not use this directly.
 */
// TODO: treat encrypted data
@TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
public class MediaTranscoderEngine {
	private static final String TAG = MediaTranscoderEngine.class.getSimpleName();
	private static final float PROGRESS_UNKNOWN = -1.0f;
	private static final long SLEEP_TO_WAIT_TRACK_TRANSCODERS = 10;
	private static final long PROGRESS_INTERVAL_STEPS = 10;
	private FileDescriptor mInputFileDescriptor;
	private TrackTranscoder mVideoTrackTranscoder;
	private TrackTranscoder mAudioTrackTranscoder;
	private MediaExtractor mExtractor;
	private MediaMuxer mMuxer;
	private volatile float mProgress;
	private ProgressCallback mProgressCallback;
	private long mDurationUs;
	private String mSnapshotPath;
	private float mSnapshotFrequency;

	/**
	 * Do not use this constructor unless you know what you are doing.
	 */
	public MediaTranscoderEngine() {
	}

	public void setDataSource(FileDescriptor fileDescriptor) {
		mInputFileDescriptor = fileDescriptor;
	}

	public void setSnapshotPath(String snapshotPath) {
		mSnapshotPath = snapshotPath;
	}

	public void setSnapshotFrequency(float snapshotFrequency) {
		mSnapshotFrequency = snapshotFrequency;
	}

	public ProgressCallback getProgressCallback() {
		return mProgressCallback;
	}

	public void setProgressCallback(ProgressCallback progressCallback) {
		mProgressCallback = progressCallback;
	}

	/**
	 * NOTE: This method is thread safe.
	 */
	public double getProgress() {
		return mProgress;
	}

	/**
	 * Run video transcoding. Blocks current thread. Audio data will not be transcoded; original stream will be wrote to output file.
	 *
	 * @param outputPath
	 *            File path to output transcoded video file.
	 * @param formatStrategy
	 *            Output format strategy.
	 * @throws IOException
	 *             when input or output file could not be opened.
	 * @throws InvalidOutputFormatException
	 *             when output format is not supported.
	 * @throws InterruptedException
	 *             when cancel to transcode.
	 */
	public void transcodeVideo(String outputPath, IMediaFormatStrategy formatStrategy) throws IOException, InterruptedException {
		if (outputPath == null) {
			throw new NullPointerException("Output path cannot be null.");
		}
		if (mInputFileDescriptor == null) {
			throw new IllegalStateException("Data source is not set.");
		}
		try {
			// NOTE: use single extractor to keep from running out audio track fast.
			mExtractor = new MediaExtractor();
			mExtractor.setDataSource(mInputFileDescriptor);
			mMuxer = new MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
			setupTrackTranscoders(formatStrategy);
			runPipelines();
			mMuxer.stop();
		} finally {
			try {
				if (mVideoTrackTranscoder != null) {
					mVideoTrackTranscoder.release();
					mVideoTrackTranscoder = null;
				}
				if (mAudioTrackTranscoder != null) {
					mAudioTrackTranscoder.release();
					mAudioTrackTranscoder = null;
				}
				if (mExtractor != null) {
					mExtractor.release();
					mExtractor = null;
				}
			} catch (RuntimeException e) {
				// Too fatal to make alive the app, because it may leak native resources.
				// noinspection ThrowFromFinallyBlock
				throw new Error("Could not shutdown extractor, codecs and muxer pipeline.", e);
			}
			try {
				if (mMuxer != null) {
					mMuxer.release();
					mMuxer = null;
				}
			} catch (RuntimeException e) {
				Log.e(TAG, "Failed to release muxer.");
			}
		}
	}

	private void setupTrackTranscoders(IMediaFormatStrategy formatStrategy) {

		MediaMetadataRetriever mediaMetadataRetriever = new MediaMetadataRetriever();
		mediaMetadataRetriever.setDataSource(mInputFileDescriptor);

		int rotation = Integer.parseInt(mediaMetadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));
		try {
			mMuxer.setOrientationHint(rotation);
		} catch (NumberFormatException e) {
			// skip
		}

		// TODO: parse ISO 6709
		// String locationString = mediaMetadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION);
		// mMuxer.setLocation(Integer.getInteger(rotationString, 0));

		try {
			mDurationUs = Long.parseLong(mediaMetadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)) * 1000;
		} catch (NumberFormatException e) {
			mDurationUs = -1;
		}
		Log.d(TAG, "Duration (us): " + mDurationUs+", rotation:" + rotation);

		MediaExtractorUtils.TrackResult trackResult = MediaExtractorUtils.getFirstVideoAndAudioTrack(mExtractor);
		MediaFormat videoOutputFormat = null;
		if(trackResult.mVideoTrackIndex != -1 ) {
			videoOutputFormat = formatStrategy.createVideoOutputFormat(trackResult.mVideoTrackFormat);
		}

		MediaFormat audioOutputFormat = null;
		if(trackResult.mAudioTrackIndex != -1) {
			audioOutputFormat = formatStrategy.createAudioOutputFormat(trackResult.mAudioTrackFormat);
		}
		if (videoOutputFormat == null ) {
			throw new InvalidOutputFormatException("IMediaFormatStrategy returned pass-through for both video and audio. No transcoding is necessary.");
		}

		QueuedMuxer queuedMuxer = new QueuedMuxer(mMuxer, new QueuedMuxer.Listener() {
			@Override
			public void onDetermineOutputFormat() {
				if(mVideoTrackTranscoder != null) {
					MediaFormatValidator.validateVideoOutputFormat(mVideoTrackTranscoder.getDeterminedFormat());
				}
				if(mAudioTrackTranscoder != null) {
					MediaFormatValidator.validateAudioOutputFormat(mAudioTrackTranscoder.getDeterminedFormat());
				}
			}
		});


		mVideoTrackTranscoder = new VideoTrackTranscoder(mExtractor, trackResult.mVideoTrackIndex, videoOutputFormat, queuedMuxer,rotation);
		mVideoTrackTranscoder.setSnapshotPath(mSnapshotPath);
		mVideoTrackTranscoder.setSnapshotFrequency(mSnapshotFrequency);
		mVideoTrackTranscoder.setup();
		mExtractor.selectTrack(trackResult.mVideoTrackIndex);

		if (audioOutputFormat != null) {
			mAudioTrackTranscoder = new PassThroughTrackTranscoder(mExtractor, trackResult.mAudioTrackIndex, queuedMuxer, QueuedMuxer.SampleType.AUDIO);
			mAudioTrackTranscoder.setup();
			mExtractor.selectTrack(trackResult.mAudioTrackIndex);
		}

		if(mAudioTrackTranscoder == null) {
			queuedMuxer.setEnabledAudio(false);
		}
	}

	private void runPipelines() {
		long loopCount = 0;
		if (mDurationUs <= 0) {
			float progress = PROGRESS_UNKNOWN;
			mProgress = progress;
			if (mProgressCallback != null)
				mProgressCallback.onProgress(progress); // unknown
		}
		if(mAudioTrackTranscoder == null){
			runPipelinesForOnlyVideo();
		}else {
			runPipelinesForVideoAndAudio();
		}
	}

	private void runPipelinesForOnlyVideo(){
		long loopCount = 0;

		while (!mVideoTrackTranscoder.isFinished()) {
			boolean stepped = mVideoTrackTranscoder.stepPipeline();
			loopCount++;
			if (mDurationUs > 0 && loopCount % PROGRESS_INTERVAL_STEPS == 0) {
				double videoProgress = mVideoTrackTranscoder.isFinished() ? 1.0 : Math.min(1.0, (double) mVideoTrackTranscoder.getWrittenPresentationTimeUs() / mDurationUs);
				float progress =(float) videoProgress;
				mProgress = progress;
				if (mProgressCallback != null)
					mProgressCallback.onProgress(progress);
			}
			if (!stepped) {
				try {
					Thread.sleep(SLEEP_TO_WAIT_TRACK_TRANSCODERS);
				} catch (InterruptedException e) {
					// nothing to do
				}
			}
		}
	}

	private void runPipelinesForVideoAndAudio(){
		long loopCount = 0;
		while (!(mVideoTrackTranscoder.isFinished() && mAudioTrackTranscoder.isFinished())) {
			boolean stepped = mVideoTrackTranscoder.stepPipeline() || mAudioTrackTranscoder.stepPipeline();
			loopCount++;
			if (mDurationUs > 0 && loopCount % PROGRESS_INTERVAL_STEPS == 0) {
				double videoProgress = mVideoTrackTranscoder.isFinished() ? 1.0 : Math.min(1.0, (double) mVideoTrackTranscoder.getWrittenPresentationTimeUs() / mDurationUs);
				double audioProgress = mAudioTrackTranscoder.isFinished() ? 1.0 : Math.min(1.0, (double) mAudioTrackTranscoder.getWrittenPresentationTimeUs() / mDurationUs);
				float progress =(float) ((videoProgress + audioProgress) / 2.0);
				mProgress = progress;
				if (mProgressCallback != null)
					mProgressCallback.onProgress(progress);
			}
			if (!stepped) {
				try {
					Thread.sleep(SLEEP_TO_WAIT_TRACK_TRANSCODERS);
				} catch (InterruptedException e) {
					// nothing to do
				}
			}
		}
	}

	public interface ProgressCallback {
		/**
		 * Called to notify progress. Same thread which initiated transcode is used.
		 *
		 * @param progress
		 *            Progress in [0.0, 1.0] range, or negative value if progress is unknown.
		 */
		void onProgress(float progress);
	}
}
