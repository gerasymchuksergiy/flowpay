const money = n => new Intl.NumberFormat('uk-UA', {
  style: 'currency', currency: 'UAH', maximumFractionDigits: 0
}).format(n);

const dateLabel = s => new Intl.DateTimeFormat('uk-UA', {
  day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit'
}).format(new Date(s));

let products = [];
let active = 'Усі';

const change = p => {
  const history = p.history || [];
  if (history.length < 2) return 0;
  const first = history[0].price;
  return first ? ((p.price - first) / first) * 100 : 0;
};

function chart(history = [], id = 'product') {
  if (!history.length) return '';
  const values = history.map(x => x.price);
  const min = Math.min(...values);
  const max = Math.max(...values);
  const width = 300;
  const height = 72;
  const pad = 5;
  const span = max - min || 1;
  const gradientId = 'g-' + id.replace(/[^a-z0-9-]/gi, '');
  const points = values.map((value, index) =>
    `${pad + index * (width - pad * 2) / Math.max(1, values.length - 1)},${height - pad - (value - min) * (height - pad * 2) / span}`
  ).join(' ');
  const color = values.at(-1) <= values[0] ? '#34c759' : '#ff3b30';
  return `<svg viewBox="0 0 ${width} ${height}" preserveAspectRatio="none" role="img" aria-label="Графік історії ціни">
    <defs><linearGradient id="${gradientId}" x1="0" y1="0" x2="0" y2="1">
      <stop stop-color="${color}" stop-opacity=".2"/><stop offset="1" stop-color="${color}" stop-opacity="0"/>
    </linearGradient></defs>
    <polygon points="${pad},${height} ${points} ${width - pad},${height}" fill="url(#${gradientId})"/>
    <polyline points="${points}" fill="none" stroke="${color}" stroke-width="2.3" vector-effect="non-scaling-stroke"/>
  </svg>`;
}

function render() {
  const list = products.filter(p => active === 'Усі' || p.category === active).sort((a, b) => {
    if (sort.value === 'priceAsc') return a.price - b.price;
    if (sort.value === 'priceDesc') return b.price - a.price;
    if (sort.value === 'updated') return new Date(b.updatedAt) - new Date(a.updatedAt);
    return change(a) - change(b);
  });
  productGrid.innerHTML = '';
  empty.hidden = Boolean(list.length);

  for (const product of list) {
    const node = cardTemplate.content.cloneNode(true);
    const delta = change(product);
    const history = product.history || [];
    const low = Math.min(...history.map(x => x.price), product.price);
    const high = Math.max(...history.map(x => x.price), product.price);
    node.querySelectorAll('.product-link').forEach(link => link.href = product.url);

    const image = node.querySelector('.product-image');
    image.src = product.image;
    image.alt = product.name;
    image.onerror = () => image.src = 'https://placehold.co/900x700/f5f5f7/1d1d1f?text=FlowPay';

    const badge = node.querySelector('.badge');
    badge.textContent = product.needsExactUrl
      ? 'Потрібне точне посилання'
      : product.price <= low ? 'Найнижча ціна' : delta < 0 ? 'Ціна падає' : 'Відстежується';
    if (!product.needsExactUrl && product.price <= low) badge.classList.add('best');

    node.querySelector('.category').textContent = product.category;
    node.querySelector('.title').textContent = product.name;
    node.querySelector('.price').textContent = money(product.price);
    node.querySelector('.old-price').textContent = product.oldPrice > product.price ? money(product.oldPrice) : '';
    const changeLabel = node.querySelector('.change');
    changeLabel.textContent = `${delta > 0 ? '+' : ''}${delta.toFixed(1)}%`;
    changeLabel.className = `change ${delta < 0 ? 'down' : delta > 0 ? 'up' : 'flat'}`;
    node.querySelector('.range').textContent = `${money(low)} — ${money(high)}`;
    node.querySelector('.chart').innerHTML = chart(history, product.id);
    node.querySelector('.updated').textContent = `Оновлено ${dateLabel(product.updatedAt)}`;
    productGrid.append(node);
  }
}

function setup() {
  const categories = ['Усі', ...new Set(products.map(p => p.category))];
  filters.innerHTML = categories.map(category =>
    `<button class="${category === active ? 'active' : ''}" aria-pressed="${category === active}" data-cat="${category}">${category}</button>`
  ).join('');
  filters.onclick = event => {
    if (!event.target.dataset.cat) return;
    active = event.target.dataset.cat;
    setup();
    render();
  };
  trackedCount.textContent = products.length;
  const drops = products.filter(p => change(p) < 0);
  dropCount.textContent = drops.length;
  bestSaving.textContent = drops.length ? `${Math.abs(Math.min(...drops.map(change))).toFixed(0)}%` : '0%';
}

sort.onchange = render;
fetch(`data/products.json?v=${Date.now()}`)
  .then(response => {
    if (!response.ok) throw Error(response.status);
    return response.json();
  })
  .then(data => {
    products = data.products || [];
    syncText.textContent = `Оновлено ${dateLabel(data.updatedAt)}`;
    setup();
    render();
  })
  .catch(() => {
    syncText.textContent = 'Не вдалося завантажити ціни';
    productGrid.innerHTML = '<div class="empty">Дані тимчасово недоступні. Оновіть сторінку пізніше.</div>';
  });
