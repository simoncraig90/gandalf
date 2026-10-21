#!/bin/sh
# Private RVC voice-conversion environment (Applio, MIT). Nothing here is committed: work/, data/, models/ are gitignored.
# Dataset goes in voice/data/rvc_ds/*.wav (clean single-speaker clips). PRIVATE USE ONLY: never publish or share clips or model.
set -e
cd "$(dirname "$0")"
mkdir -p work/rvc models
[ -d work/rvc/Applio ] || git clone --depth 1 https://github.com/IAHispano/Applio work/rvc/Applio
[ -d work/rvc/.venv ] || uv venv --python 3.11 work/rvc/.venv
. work/rvc/.venv/bin/activate
(cd work/rvc/Applio && uv pip install -r requirements-engine.txt && python core.py prerequisites)
cat <<'T'
Train (Mac MPS; embedding extraction must run on CPU, --gpu -):
  cd voice/work/rvc/Applio; export PYTORCH_ENABLE_MPS_FALLBACK=1
  python core.py preprocess --model-name gandalf --dataset-path <abs>/voice/data/rvc_ds --sample-rate 40000 --cpu-cores 6 --cut-preprocess Automatic --normalization-mode post
  python core.py extract --model-name gandalf --f0-method rmvpe --cpu-cores 6 --gpu - --sample-rate 40000 --embedder-model contentvec
  python core.py train --model-name gandalf --vocoder HiFi-GAN --save-every-epoch 25 --save-every-weights --total-epoch 300 --sample-rate 40000 --batch-size 8 --gpu 0 --pretrained --index-algorithm Auto
Then copy logs/gandalf/*.index and weights to voice/models/gandalf.index / gandalf.pth.
Switch engine: projects.json "voice_engine": "rvc" (optional rvc_base_voice, rvc_pitch, rvc_index_rate); falls back to kokoro on error.
T
