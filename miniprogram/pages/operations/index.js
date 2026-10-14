function clockMinute(n) {
  return (
    String(Math.floor(n / 60)).padStart(2, "0") +
    ":" +
    String(n % 60).padStart(2, "0")
  );
}
const api = require("../../request");
const money = (n) => (Number(n) / 100).toFixed(2);
Page({
  data: {
    actor: null,
    tab: "finance",
    config: { stores: [], rooms: [], packages: [] },
    staff: [],
    finance: { payments: [], refunds: [], commands: [], alerts: [] },
    fields: [],
    editor: null,
    busy: false,
  },
  async onLoad() {
    this.setData({ actor: getApp().globalData.actor });
    if (!this.data.actor || this.data.actor.role === "USER") {
      wx.showToast({ title: "需要管理员身份", icon: "none" });
      return;
    }
    await this.load();
  },
  async onPullDownRefresh() {
    try {
      await this.load();
    } finally {
      wx.stopPullDownRefresh();
    }
  },
  async load() {
    try {
      const f = await api("/finance");
      ["payments", "refunds"].forEach(
        (k) =>
          (f[k] = f[k].map((x) => ({ ...x, price: money(x.amount_cents) }))),
      );
      this.setData({ finance: f });
      if (this.data.actor.role === "OWNER") {
        const [config, staff] = await Promise.all([
          api("/configuration"),
          api("/staff"),
        ]);
        this.setData({ config, staff });
      }
    } catch (e) {
      wx.showToast({ title: e.message, icon: "none" });
    }
  },
  tab(e) {
    this.setData({ tab: e.currentTarget.dataset.tab, editor: null });
  },
  edit(e) {
    const { kind, id } = e.currentTarget.dataset;
    const list =
      kind === "staff"
        ? this.data.staff
        : this.data.config[
            kind === "store" ? "stores" : kind === "room" ? "rooms" : "packages"
          ];
    const r = list.find((x) => String(x.id) === String(id)) || {};
    let fields = [];
    const n = (key, label, value) => ({ key, label, value, type: "number" }),
      s = (key, label, value) => ({
        key,
        label,
        value: value || "",
        type: "text",
      });
    const stores = this.data.config.stores,
      rooms = this.data.config.rooms;
    const select = (key, label, value, options) => ({
      key,
      label,
      value,
      options,
      range: options.map((x) => x.name),
      index: Math.max(
        0,
        options.findIndex((x) => String(x.id) === String(value)),
      ),
      type: "select",
    });
    if (kind === "store")
      fields = [
        s("name", "门店名称", r.name),
        s("address", "地址", r.address),
        {
          ...s(
            "openMinute",
            "营业开始（如10:00）",
            clockMinute(r.open_minute ?? 0),
          ),
          clock: true,
        },
        {
          ...s(
            "closeMinute",
            "营业结束（如24:00）",
            clockMinute(r.close_minute ?? 1440),
          ),
          clock: true,
        },
        n("cleaningMinutes", "保洁缓冲（分钟）", r.cleaning_minutes ?? 15),
        n("earlyMinutes", "提前入场（分钟）", r.early_minutes ?? 0),
        n("cancelMinutes", "取消截止（提前分钟）", r.cancel_minutes ?? 60),
      ];
    if (kind === "room")
      fields = [
        s("name", "包间名称", r.name),
        select("storeId", "所属门店", r.store_id ?? stores[0]?.id, stores),
        n("capacity", "人数", r.capacity ?? 4),
        {
          ...n("priceCents", "小时价格（元）", (r.price_cents ?? 3800) / 100),
          money: true,
        },
      ];
    if (kind === "package")
      fields = [
        s("name", "套餐名称", r.name),
        select("roomId", "包间", r.room_id ?? rooms[0]?.id, rooms),
        n("minutes", "套餐时长（分钟）", r.minutes ?? 120),
        {
          ...n("amountCents", "套餐价格（元）", (r.amount_cents ?? 6000) / 100),
          money: true,
        },
      ];
    if (kind === "staff")
      fields = [
        s("username", "登录账号", r.username),
        {
          ...s(
            "password",
            id ? "新密码（留空保持）" : "初始密码（至少12位）",
            "",
          ),
          password: true,
        },
        select("storeId", "负责门店", r.store_id ?? stores[0]?.id, stores),
      ];
    fields.push(
      select("enabled", "状态", r.enabled !== false, [
        { id: true, name: "启用" },
        { id: false, name: "停用 / 下架" },
      ]),
    );
    this.setData({ editor: { kind, id: id || null }, fields });
  },
  input(e) {
    const fields = this.data.fields.map((f) =>
      f.key === e.currentTarget.dataset.key
        ? { ...f, value: e.detail.value }
        : f,
    );
    this.setData({ fields });
  },
  pick(e) {
    const fields = this.data.fields.map((f) =>
      f.key === e.currentTarget.dataset.key
        ? {
            ...f,
            index: Number(e.detail.value),
            value: f.options[Number(e.detail.value)].id,
          }
        : f,
    );
    this.setData({ fields });
  },
  async save() {
    if (this.data.busy) return;
    this.setData({ busy: true });
    try {
      const b = {};
      this.data.fields.forEach(
        (f) =>
          (b[f.key] =
            f.type === "number"
              ? f.money
                ? Math.round(Number(f.value) * 100)
                : Number(f.value)
              : f.type === "select"
                ? f.options[f.index]?.id
                : f.clock
                  ? Number(f.value.split(":")[0]) * 60 +
                    Number(f.value.split(":")[1])
                  : f.value),
      );
      const { kind, id } = this.data.editor;
      let path =
          "/" +
          {
            store: "stores",
            room: "rooms",
            package: "packages",
            staff: "staff",
          }[kind],
        method = "POST";
      if (["store", "room"].includes(kind) && id) {
        path += "/" + id;
        method = "PUT";
      } else if (id) b.id = id;
      if (kind === "staff") b.role = "MANAGER";
      await api(path, method, b);
      this.setData({ editor: null });
      await this.load();
      wx.showToast({ title: "已保存" });
    } catch (e) {
      wx.showToast({ title: e.message, icon: "none", duration: 3000 });
    } finally {
      this.setData({ busy: false });
    }
  },
  close() {
    this.setData({ editor: null });
  },
  async retry(e) {
    try {
      await api(
        "/refunds/" + e.currentTarget.dataset.id + "/retry",
        "POST",
        {},
      );
      await this.load();
    } catch (err) {
      wx.showToast({ title: err.message, icon: "none" });
    }
  },
});
