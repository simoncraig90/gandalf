#!/bin/sh
# Rebuilds the private voice-clone environment (nothing here is committed: .venv, ref, work, samples are gitignored).
# Reference clips go in voice/ref/gandalf_*.wav (mono, 24 kHz, 10-30 s each). PRIVATE USE ONLY: never publish or share them.
set -e
cd "$(dirname "$0")"
uv venv --python 3.11 .venv
. .venv/bin/activate
uv pip install yt-dlp imageio-ffmpeg faster-whisper chatterbox-tts demucs "setuptools<81" \
  librosa pypinyin jieba cn2an eng_to_ipa inflect unidecode langid pydub
mkdir -p work/ov && [ -d work/ov/src ] || git clone --depth 1 https://github.com/myshell-ai/OpenVoice work/ov/src
python -c "from huggingface_hub import snapshot_download as s; s('myshell-ai/OpenVoiceV2', local_dir='work/ov/ckpt')"
echo "Switch engine: projects.json \"voice_engine\": \"kokoro\" | \"openvoice\" | \"chatterbox\" (falls back to kokoro on error)"
