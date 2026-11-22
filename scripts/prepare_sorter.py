"""
Prepares the on-device photo sorter for the build (runs in GitHub Actions).

1. Downloads MobileCLIP-S0: the full-precision image model (bundled in the APK), the full-precision
   text model (used here only, to describe the categories), and a smaller text model + tokenizer
   for the optional in-app "Search & sort" download.
2. Works out the right image preparation by testing on real sample photos.
3. ACCURACY GATE: if the sorter can't tell a cat from a receipt, the build stops here
   instead of shipping a bad sorter.
"""
import io
import json
import os
import shutil
import sys
import time
import urllib.request

import numpy as np
import onnxruntime as ort
from PIL import Image, ImageDraw, ImageFont
from tokenizers import Tokenizer

BASE = "https://huggingface.co/Xenova/mobileclip_s0/resolve/main/"
SAMPLES = "https://huggingface.co/datasets/huggingface/documentation-images/resolve/main/"
CACHE = ".sorter-cache"
ASSETS = "app/src/main/assets/sorter"
RELEASE = "out/sorter"
SIZE = 256
SCALE = 100.0      # CLIP logit scale
MIN_PROB = 0.50    # how sure it must be to file a photo
MIN_MARGIN = 0.15  # ...and how far ahead of the runner-up

GROUPS = [
    ("screens", "Screenshots & chats"),
    ("memes", "Memes & comics"),
    ("cards", "Trading cards"),
    ("docs", "Documents & notes"),
    ("pets", "Pets & animals"),
    ("people", "People"),
    ("food", "Food & drinks"),
    ("outdoors", "Nature & outdoors"),
    ("places", "Places & buildings"),
    ("vehicles", "Vehicles"),
    ("games", "Video games"),
    ("art", "Anime & art"),
    ("things", "Things & tech"),
    ("events", "Events & sports"),
]

# key, name, simplified group, descriptions (averaged)
CATEGORIES = [
    ("screenshots", "Screenshots", "screens", ["a screenshot of a phone screen", "a screenshot of an app", "a screenshot of a website", "a screenshot of settings menu"]),
    ("chats", "Chats & messages", "screens", ["a screenshot of a text message conversation", "a screenshot of chat bubbles", "a screenshot of a social media post"]),
    ("memes", "Memes", "memes", ["an internet meme with caption text", "a funny meme image", "a reaction meme"]),
    ("comics", "Comics & manga", "memes", ["a comic strip with panels", "a manga page", "a webcomic with speech bubbles"]),
    ("cards", "Trading cards", "cards", ["a trading card game card", "a Magic: The Gathering card", "a Pokemon trading card", "a collectible card with art and rules text"]),
    ("documents", "Documents", "docs", ["a photo of a printed document", "a page of text", "a scanned form or letter"]),
    ("receipts", "Receipts & bills", "docs", ["a photo of a shopping receipt", "a paper receipt with prices", "a bill or invoice"]),
    ("notes", "Notes & lists", "docs", ["handwritten notes on paper", "a written list", "notes in a notebook"]),
    ("codes", "Codes & tickets", "docs", ["a QR code", "a ticket or boarding pass", "a barcode"]),
    ("pets", "Pets", "pets", ["a photo of a cat", "a photo of a dog", "a pet cat at home", "a pet dog"]),
    ("animals", "Animals", "pets", ["a photo of a wild animal", "a photo of a bird", "an animal outdoors"]),
    ("selfies", "Selfies", "people", ["a selfie", "a close-up photo of a person's face"]),
    ("people", "People", "people", ["a photo of a person", "a portrait photo of someone"]),
    ("groups", "Friends & family", "people", ["a group photo of people", "friends posing together", "a family photo"]),
    ("food", "Food", "food", ["a photo of food", "a meal on a plate", "a dessert", "a pastry"]),
    ("drinks", "Drinks", "food", ["a drink in a glass", "a cup of coffee", "a cocktail"]),
    ("nature", "Nature", "outdoors", ["a nature landscape", "a forest", "mountains and trees"]),
    ("sky", "Sky & sunsets", "outdoors", ["a sunset", "the sky with clouds", "the night sky with stars"]),
    ("water", "Beach & water", "outdoors", ["a beach", "a lake", "the ocean"]),
    ("plants", "Plants & flowers", "outdoors", ["flowers", "a house plant", "a garden"]),
    ("city", "City & buildings", "places", ["a city street", "buildings and architecture", "a skyline"]),
    ("home", "Home & rooms", "places", ["the inside of a room", "a living room", "a bedroom"]),
    ("maps", "Maps & listings", "places", ["a map", "directions on a map", "a listing for an apartment"]),
    ("vehicles", "Cars & vehicles", "vehicles", ["a photo of a car", "a truck", "a motorcycle"]),
    ("games", "Video games", "games", ["a screenshot of a video game", "a Minecraft screenshot", "video game graphics"]),
    ("anime", "Anime", "art", ["an anime screenshot", "anime artwork", "an anime character"]),
    ("art", "Art & drawings", "art", ["a digital illustration", "a drawing", "a painting"]),
    ("products", "Shopping & products", "things", ["a product photo", "an item for sale", "an online shopping listing"]),
    ("tech", "Tech & gadgets", "things", ["a computer setup", "electronics and gadgets", "a phone or device"]),
    ("sports", "Sports & fitness", "events", ["people playing sports", "a workout at the gym"]),
    ("events", "Events & parties", "events", ["a party", "a concert", "a birthday celebration"]),
]

# Sample photos for the accuracy gate: (file in the sample set, expected group)
TESTS = [
    ("pipeline-cat-chonk.jpeg", "pets"),
    ("image_process_dog.png", "pets"),
    ("baklava.jpg", "food"),
    ("doc_1.png", "docs"),
    ("notifications-settings.png", "screens"),
]

CLIP_MEAN = [0.48145466, 0.4578275, 0.40821073]
CLIP_STD = [0.26862954, 0.26130258, 0.27577711]


def fetch(url: str, dest: str, required: bool = True) -> str | None:
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    if os.path.exists(dest) and os.path.getsize(dest) > 0:
        print(f"cached: {os.path.basename(dest)}")
        return dest
    for attempt in range(1, 6):
        try:
            print(f"downloading {url} (try {attempt})")
            req = urllib.request.Request(url, headers={"User-Agent": "stellar-gallery-build"})
            with urllib.request.urlopen(req, timeout=180) as r, open(dest + ".part", "wb") as f:
                shutil.copyfileobj(r, f)
            os.replace(dest + ".part", dest)
            return dest
        except Exception as e:  # noqa: BLE001
            print(f"  failed: {e}")
            time.sleep(5 * attempt)
    if required:
        sys.exit(f"Could not download {url} after 5 tries.")
    return None


def model(path: str, name: str) -> str:
    return fetch(BASE + path, os.path.join(CACHE, name))


def normalize(v: np.ndarray) -> np.ndarray:
    return v / max(float(np.linalg.norm(v)), 1e-9)


def prep(img: Image.Image, norm: bool) -> np.ndarray:
    """Same steps as the app: shortest side → 256 (bilinear), center crop 256, 0..1, optional CLIP normalize."""
    img = img.convert("RGB")
    w, h = img.size
    s = SIZE / min(w, h)
    nw, nh = max(SIZE, round(w * s)), max(SIZE, round(h * s))
    img = img.resize((nw, nh), Image.BILINEAR)
    left, top = (nw - SIZE) // 2, (nh - SIZE) // 2
    img = img.crop((left, top, left + SIZE, top + SIZE))
    a = np.asarray(img, dtype=np.float32) / 255.0
    if norm:
        a = (a - np.array(CLIP_MEAN, dtype=np.float32)) / np.array(CLIP_STD, dtype=np.float32)
    return a.transpose(2, 0, 1)[None, ...].astype(np.float32)


def fake_receipt() -> Image.Image:
    bg = Image.new("RGB", (720, 960), (88, 84, 80))
    paper = Image.new("RGB", (420, 860), (246, 244, 238))
    d = ImageDraw.Draw(paper)
    try:
        font = ImageFont.load_default(size=22)
    except TypeError:
        font = ImageFont.load_default()
    lines = ["  GROCERY MART #204", "  123 MAIN ST", "-" * 30, "MILK 2L            4.29", "BREAD              3.49",
             "EGGS DOZEN         5.99", "APPLES 1.2KG       4.76", "COFFEE             9.99", "-" * 30,
             "SUBTOTAL          28.52", "TAX                1.43", "TOTAL             29.95", "", "VISA ****1234",
             "  THANK YOU!", "  11/21/2026 14:32"]
    for i, t in enumerate(lines):
        d.text((24, 30 + i * 44), t, fill=(30, 30, 30), font=font)
    bg.paste(paper, (150, 50))
    return bg


def main() -> None:
    vision_path = model("onnx/vision_model.onnx", "vision_fp32.onnx")
    text_full = model("onnx/text_model.onnx", "text_fp32.onnx")
    text_small = model("onnx/text_model_quantized.onnx", "text_int8.onnx")
    tok_path = model("tokenizer.json", "tokenizer.json")
    lic_path = model("LICENSE", "LICENSE.txt")

    vis = ort.InferenceSession(vision_path, providers=["CPUExecutionProvider"])
    vin = vis.get_inputs()[0].name
    vout = next((o.name for o in vis.get_outputs() if o.name == "image_embeds"), vis.get_outputs()[0].name)

    tok = Tokenizer.from_file(tok_path)
    pad_id = tok.token_to_id("!")
    tok.enable_padding(length=77, pad_id=pad_id if pad_id is not None else 0, pad_token="!")
    tok.enable_truncation(max_length=77)

    def text_embedder(path: str):
        sess = ort.InferenceSession(path, providers=["CPUExecutionProvider"])
        names = [i.name for i in sess.get_inputs()]
        out = next((o.name for o in sess.get_outputs() if o.name == "text_embeds"), sess.get_outputs()[0].name)

        def embed(s: str) -> np.ndarray:
            enc = tok.encode(s)
            feeds = {n: np.array([enc.attention_mask if "mask" in n else enc.ids], dtype=np.int64) for n in names}
            return normalize(sess.run([out], feeds)[0][0].astype(np.float32))
        return embed

    embed_full = text_embedder(text_full)
    embed_small = text_embedder(text_small)

    # --- category vectors (full precision) ---
    cats = []
    for key, name, group, prompts in CATEGORIES:
        vec = normalize(np.mean([embed_full(p) for p in prompts], axis=0))
        cats.append({"key": key, "name": name, "group": group, "emb": vec})
    mat = np.stack([c["emb"] for c in cats])
    dim = mat.shape[1]

    def group_of(probs: np.ndarray) -> tuple[str, float, float]:
        sums: dict[str, float] = {}
        for c, p in zip(cats, probs):
            sums[c["group"]] = sums.get(c["group"], 0.0) + float(p)
        ranked = sorted(sums.items(), key=lambda kv: -kv[1])
        return ranked[0][0], ranked[0][1], ranked[0][1] - (ranked[1][1] if len(ranked) > 1 else 0.0)

    # --- sample photos ---
    samples = []
    for fname, expected in TESTS:
        p = fetch(SAMPLES + fname, os.path.join(CACHE, "samples", fname), required=False)
        if p:
            try:
                samples.append((fname, expected, Image.open(p)))
            except Exception as e:  # noqa: BLE001
                print(f"  couldn't open {fname}: {e}")
    samples.append(("generated receipt", "docs", fake_receipt()))

    def evaluate(norm: bool):
        correct, rows = 0, []
        for fname, expected, img in samples:
            emb = normalize(vis.run([vout], {vin: prep(img, norm)})[0][0].astype(np.float32))
            if emb.shape[0] != dim:
                sys.exit(f"Image/text embedding sizes differ ({emb.shape[0]} vs {dim}).")
            logits = SCALE * mat @ emb
            probs = np.exp(logits - logits.max())
            probs /= probs.sum()
            got, conf, margin = group_of(probs)
            ok = got == expected
            correct += ok
            rows.append(f"  {'OK ' if ok else 'BAD'} {fname:32s} expected {expected:8s} got {got:8s} ({conf:.2f}, margin {margin:.2f})")
        return correct, rows

    results = {}
    for norm in (False, True):
        c, rows = evaluate(norm)
        results[norm] = (c, rows)
        print(f"\nImage prep {'with' if norm else 'without'} CLIP normalization: {c}/{len(samples)} correct")
        print("\n".join(rows))
    best_norm = max(results, key=lambda k: results[k][0])
    best = results[best_norm][0]
    needed = max(3, len(samples) - 2)
    print(f"\nUsing image prep {'with' if best_norm else 'without'} normalization ({best}/{len(samples)}).")
    if best < needed:
        sys.exit(f"ACCURACY GATE FAILED: sorter got only {best}/{len(samples)} sample photos right (need {needed}). "
                 "Not shipping a bad sorter — paste this log to Claude.")

    # --- search model check: small text model must agree with the full one ---
    probes = ["a photo of a cat", "a receipt", "a sunset over the ocean", "an anime character", "a screenshot of a chat"]
    agree = min(float(np.dot(embed_full(p), embed_small(p))) for p in probes)
    print(f"Search model agreement with full model: {agree:.3f}")
    search_model = text_small
    if agree < 0.90:
        print("Small text model drifts too much — shipping the half-precision one for Search & sort instead.")
        search_model = model("onnx/text_model_fp16.onnx", "text_fp16.onnx")

    os.makedirs(ASSETS, exist_ok=True)
    shutil.copyfile(vision_path, os.path.join(ASSETS, "vision.onnx"))
    shutil.copyfile(lic_path, os.path.join(ASSETS, "MOBILECLIP_LICENSE.txt"))
    with open(os.path.join(ASSETS, "categories.json"), "w") as f:
        json.dump({
            "dim": dim,
            "model": "mobileclip_s0_fp32_v2",
            "preprocess": {"size": SIZE, "normalize": best_norm, "mean": CLIP_MEAN, "std": CLIP_STD},
            "decision": {"scale": SCALE, "min_prob": MIN_PROB, "min_margin": MIN_MARGIN},
            "groups": [{"key": k, "name": n} for k, n in GROUPS],
            "categories": [
                {"key": c["key"], "name": c["name"], "group": c["group"], "emb": [round(float(x), 6) for x in c["emb"]]}
                for c in cats
            ],
        }, f)

    os.makedirs(RELEASE, exist_ok=True)
    shutil.copyfile(search_model, os.path.join(RELEASE, "stellar-sorter-text.onnx"))
    shutil.copyfile(tok_path, os.path.join(RELEASE, "stellar-sorter-tokenizer.json"))
    print(f"Photo sorter ready: {len(cats)} categories in {len(GROUPS)} simplified folders, {dim}-d embeddings.")


if __name__ == "__main__":
    main()
