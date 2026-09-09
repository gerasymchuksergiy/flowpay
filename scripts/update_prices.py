import json, os, re, time
from datetime import datetime, timezone
from pathlib import Path
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / "data" / "products.json"
raw_keys = os.environ.get("GEMINI_API_KEYS") or os.environ.get("GEMINI_API_KEY", "")
API_KEYS = [key.strip() for key in re.split(r"[,\n]+", raw_keys) if key.strip()]
MODEL = os.environ.get("GEMINI_MODEL", "gemini-2.5-flash")

def get(url):
    req = Request(url, headers={"User-Agent":"Mozilla/5.0 FlowPayPriceTracker/1.0","Accept-Language":"uk-UA,uk;q=0.9"})
    with urlopen(req, timeout=25) as r:
        return r.read(900_000).decode("utf-8", "ignore")

def extract_price(product, html):
    prompt = f'''Verify that this page sells the exact product "{product['name']}" and extract its currently purchasable full price in UAH. Ignore installments, crossed-out prices, accessories, different variants and unrelated products. Return ONLY JSON: {{"matchedProduct": boolean, "available": boolean, "price": number|null, "confidence": number}}. Page text:\n{re.sub(r'<[^>]+>', ' ', html)[:120000]}'''
    url = f"https://generativelanguage.googleapis.com/v1beta/models/{MODEL}:generateContent"
    body = json.dumps({"contents":[{"parts":[{"text":prompt}]}],"generationConfig":{"responseMimeType":"application/json","temperature":0}}).encode()
    last_error = None
    for index, api_key in enumerate(API_KEYS, 1):
        try:
            req = Request(url, data=body, headers={"Content-Type":"application/json", "x-goog-api-key":api_key})
            with urlopen(req, timeout=60) as r:
                result = json.load(r)
            break
        except Exception as exc:
            last_error = exc
            print(f"::warning title=Gemini key {index}::Key failed, trying the next configured key")
    else:
        raise RuntimeError(f"all Gemini keys failed: {last_error}")
    text = result["candidates"][0]["content"]["parts"][0]["text"]
    parsed = json.loads(text)
    if not parsed.get("matchedProduct") or not parsed.get("available") or parsed.get("confidence", 0) < 0.8:
        raise ValueError("product identity, availability or confidence check failed")
    return parsed.get("price")

def main():
    if not API_KEYS:
        raise SystemExit("GEMINI_API_KEYS or GEMINI_API_KEY is required")
    data = json.loads(DATA.read_text())
    now = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
    day = now[:10]
    changed = 0
    for product in data["products"]:
        if product.get("needsExactUrl"):
            print(f"::notice title={product['name']}::Exact product URL is required before automatic tracking")
            continue
        try:
            price = extract_price(product, get(product["url"]))
            if not isinstance(price, (int,float)) or price <= 0 or not (product["price"] * .45 <= price <= product["price"] * 1.8):
                raise ValueError(f"suspicious price: {price}")
            old = product["price"]
            product["oldPrice"] = max(product.get("oldPrice", old), old)
            product["price"] = round(price, 2)
            product["updatedAt"] = now
            history = [x for x in product.get("history", []) if x["date"] != day]
            history.append({"date":day,"price":product["price"]})
            product["history"] = history[-90:]
            changed += 1
        except Exception as exc:
            print(f"::warning title={product['name']}::{exc}")
        time.sleep(1)
    data["updatedAt"] = now
    DATA.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n")
    print(f"Updated {changed}/{len(data['products'])} products")

if __name__ == "__main__": main()
