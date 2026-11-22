"""
Prepares the on-device photo sorter for the build (runs in GitHub Actions).

1. Downloads the MobileCLIP-S0 image model (bundled in the APK) and the optional
   text model + tokenizer (attached to the GitHub release for "Search & sort").
2. Pre-computes the starter category descriptions, so the app can sort without the text model.
3. Test-runs both models. If anything is off, the build stops here with a clear message
   instead of shipping a broken sorter.
"""
import json
import os
import shutil
import sys
import time
import urllib.request

import numpy as np
import onnxruntime as ort
from tokenizers import Tokenizer

BASE = "https://huggingface.co/Xenova/mobileclip_s0/resolve/main/"
CACHE = ".sorter-cache"
ASSETS = "app/src/main/assets/sorter"
RELEASE = "out/sorter"

# key, display name, descriptions (averaged together)
CATEGORIES = [
    ("screenshots", "Screenshots", ["a screenshot of a phone screen", "a screenshot of an app", "a screenshot of a website"]),
    ("chats", "Chats & messages", ["a screenshot of a text message conversation", "a screenshot of a chat app"]),
    ("memes", "Memes", ["an internet meme with text", "a funny meme", "a reaction image meme"]),
    ("comics", "Comics & manga", ["a comic strip", "a manga page", "a webcomic with speech bubbles"]),
    ("cards", "Trading cards", ["a trading card game card", "a Magic: The Gathering card", "a Pokemon card"]),
    ("documents", "Documents", ["a photo of a document", "a page of printed text", "a form or letter"]),
    ("receipts", "Receipts & bills", ["a photo of a receipt", "a bill or invoice"]),
    ("notes", "Notes & lists", ["handwritten notes", "a written list", "a to-do list"]),
    ("pets", "Pets", ["a photo of a cat", "a photo of a dog", "a pet at home"]),
    ("animals", "Animals", ["a wild animal", "a bird", "an animal outdoors"]),
    ("selfies", "Selfies", ["a selfie", "a close-up photo of a person's face"]),
    ("people", "People", ["a photo of a person", "a portrait photo"]),
    ("groups", "Friends & family", ["a group photo of people", "friends together", "a family photo"]),
    ("food", "Food", ["a photo of food", "a meal on a plate", "a dessert"]),
    ("drinks", "Drinks", ["a drink in a glass", "a cup of coffee", "a cocktail"]),
    ("nature", "Nature", ["a nature landscape", "a forest", "mountains and trees"]),
    ("sky", "Sky & sunsets", ["a sunset", "the sky with clouds", "the night sky with stars"]),
    ("water", "Beach & water", ["a beach", "a lake", "the ocean"]),
    ("plants", "Plants & flowers", ["flowers", "a house plant", "a garden"]),
    ("city", "City & buildings", ["a city street", "buildings and architecture", "a skyline"]),
    ("home", "Home & rooms", ["the inside of a room", "a living room", "a bedroom"]),
    ("vehicles", "Cars & vehicles", ["a car", "a truck", "a motorcycle"]),
    ("games", "Video games", ["a screenshot of a video game", "a Minecraft screenshot", "video game graphics"]),
    ("anime", "Anime", ["an anime screenshot", "anime artwork", "an anime character"]),
    ("art", "Art & drawings", ["a digital illustration", "a drawing", "a painting"]),
    ("products", "Shopping & products", ["a product photo", "an item for sale", "an online shopping listing"]),
    ("maps", "Maps & places", ["a map", "directions on a map", "a listing for an apartment or place"]),
    ("sports", "Sports & fitness", ["people playing sports", "a workout at the gym"]),
    ("events", "Events & parties", ["a party", "a concert", "a birthday celebration"]),
    ("codes", "Codes & tickets", ["a QR code", "a ticket", "a barcode"]),
    ("tech", "Tech & gadgets", ["a computer setup", "electronics and gadgets", "a phone or device"]),
]


def download(path: str, name: str) -> str:
    os.makedirs(CACHE, exist_ok=True)
    dest = os.path.join(CACHE, name)
    if os.path.exists(dest) and os.path.getsize(dest) > 0:
        print(f"cached: {name}")
        return dest
    url = BASE + path
    for attempt in range(1, 6):
        try:
            print(f"downloading {url} (try {attempt})")
            req = urllib.request.Request(url, headers={"User-Agent": "stellar-gallery-build"})
            with urllib.request.urlopen(req, timeout=120) as r, open(dest + ".part", "wb") as f:
                shutil.copyfileobj(r, f)
            os.replace(dest + ".part", dest)
            return dest
        except Exception as e:  # noqa: BLE001
            print(f"  failed: {e}")
            time.sleep(5 * attempt)
    sys.exit(f"Could not download {name} for the photo sorter after 5 tries.")


def normalize(v: np.ndarray) -> np.ndarray:
    return v / max(float(np.linalg.norm(v)), 1e-9)


def main() -> None:
    vision_path = download("onnx/vision_model_quantized.onnx", "vision.onnx")
    text_path = download("onnx/text_model_quantized.onnx", "text.onnx")
    tok_path = download("tokenizer.json", "tokenizer.json")
    lic_path = download("LICENSE", "LICENSE.txt")

    # --- vision model test run ---
    vis = ort.InferenceSession(vision_path, providers=["CPUExecutionProvider"])
    vin = vis.get_inputs()[0]
    vout = next((o.name for o in vis.get_outputs() if o.name == "image_embeds"), vis.get_outputs()[0].name)
    print("vision input:", vin.name, vin.shape, "output:", vout)
    img = vis.run([vout], {vin.name: np.random.rand(1, 3, 256, 256).astype(np.float32)})[0]
    dim = int(img.shape[-1])
    print("embedding size:", dim)

    # --- text model ---
    tok = Tokenizer.from_file(tok_path)
    pad_id = tok.token_to_id("!")
    if pad_id is None:
        pad_id = 0
    tok.enable_padding(length=77, pad_id=pad_id, pad_token="!")
    tok.enable_truncation(max_length=77)
    txt = ort.InferenceSession(text_path, providers=["CPUExecutionProvider"])
    tin = [i.name for i in txt.get_inputs()]
    tout = next((o.name for o in txt.get_outputs() if o.name == "text_embeds"), txt.get_outputs()[0].name)
    print("text inputs:", tin, "output:", tout)

    def embed_text(s: str) -> np.ndarray:
        enc = tok.encode(s)
        feeds = {}
        for name in tin:
            if "mask" in name:
                feeds[name] = np.array([enc.attention_mask], dtype=np.int64)
            else:
                feeds[name] = np.array([enc.ids], dtype=np.int64)
        return normalize(txt.run([tout], feeds)[0][0].astype(np.float32))

    cats = []
    for key, name, prompts in CATEGORIES:
        vec = normalize(np.mean([embed_text(p) for p in prompts], axis=0))
        if vec.shape[0] != dim:
            sys.exit(f"Text/image embedding sizes differ ({vec.shape[0]} vs {dim}).")
        cats.append({"key": key, "name": name, "emb": [round(float(x), 6) for x in vec]})

    # sanity check: different descriptions should give different vectors
    a = embed_text("a photo of a cat")
    b = embed_text("a screenshot of a phone screen")
    sim = float(np.dot(a, b))
    print(f"sanity similarity cat vs screenshot: {sim:.3f}")
    if sim > 0.98:
        sys.exit("Text model returned near-identical vectors — refusing to ship a broken sorter.")

    os.makedirs(ASSETS, exist_ok=True)
    shutil.copyfile(vision_path, os.path.join(ASSETS, "vision.onnx"))
    shutil.copyfile(lic_path, os.path.join(ASSETS, "MOBILECLIP_LICENSE.txt"))
    with open(os.path.join(ASSETS, "categories.json"), "w") as f:
        json.dump({"dim": dim, "model": "mobileclip_s0", "categories": cats}, f)

    os.makedirs(RELEASE, exist_ok=True)
    shutil.copyfile(text_path, os.path.join(RELEASE, "stellar-sorter-text.onnx"))
    shutil.copyfile(tok_path, os.path.join(RELEASE, "stellar-sorter-tokenizer.json"))
    print(f"Photo sorter ready: {len(cats)} starter categories, {dim}-d embeddings.")


if __name__ == "__main__":
    main()
