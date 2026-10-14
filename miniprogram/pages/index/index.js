const labels = {
  READY: "可预约",
  IN_USE: "使用中",
  CLEANING: "待保洁",
  MAINTENANCE: "维护中",
  PENDING: "待付款",
  PAID: "已付款",
  COMPLETED: "已完成",
  CANCELLED: "已取消",
  EXPIRED: "已超时",
  NO_SHOW: "未到店",
};
const app = () => getApp();
const api = require("../../request");
const money = (n) => (n / 100).toFixed(2),
  date = (n) => {
    const d = new Date(n);
    return (
      d.getMonth() +
      1 +
      "/" +
      d.getDate() +
      " " +
      String(d.getHours()).padStart(2, "0") +
      ":" +
      String(d.getMinutes()).padStart(2, "0")
    );
  };
Page({
  data: {
    actor: null,
    tab: "rooms",
    rooms: [],
    orders: [],
    stores: [],
    storeIndex: 0,
    storeNames: ["全部门店"],
    selected: null,
    duration: 2,
    durationIndex: 1,
    durations: ["1小时", "2小时", "3小时", "4小时"],
    date: "",
    time: "",
    quote: "0",
    busy: false,
    capabilities: { demo: true },
    packages: [],
    packageNames: ["按小时计价"],
    packageIndex: 0,
    notices: [],
    username: "",
    password: "",
  },
  async onLoad() {
    try {
      this.setData({ capabilities: await api("/capabilities") });
    } catch (e) {}
    const d = new Date(Date.now() + 5 * 60000);
    this.setData({
      date:
        d.getFullYear() +
        "-" +
        String(d.getMonth() + 1).padStart(2, "0") +
        "-" +
        String(d.getDate()).padStart(2, "0"),
      time:
        String(d.getHours()).padStart(2, "0") +
        ":" +
        String(d.getMinutes()).padStart(2, "0"),
    });
  },
  async onPullDownRefresh() {
    try {
      if (this.data.actor) await this.load();
    } finally {
      wx.stopPullDownRefresh();
    }
  },
  error(e) {
    if (!app().globalData.actor) this.setData({ actor: null });
    wx.showToast({ title: e.message, icon: "none", duration: 3000 });
  },
  async login(e) {
    if (this.data.busy) return;
    this.setData({ busy: true });
    try {
      const result = await api("/demo/session", "POST", {
        account: e.currentTarget.dataset.account,
      });
      app().globalData.token = result.token;
      app().globalData.actor = result.actor;
      this.setData({ actor: result.actor, storeIndex: 0 });
      await this.load();
    } catch (err) {
      this.error(err);
    } finally {
      this.setData({ busy: false });
    }
  },
  logout() {
    app().globalData.token = "";
    app().globalData.actor = null;
    this.setData({
      actor: null,
      selected: null,
      orders: [],
      rooms: [],
      storeIndex: 0,
    });
  },
  async load() {
    const [rooms, orders, stores] = await Promise.all([
      api("/rooms"),
      api("/bookings"),
      api("/stores"),
    ]);
    this.allRooms = rooms;
    this.allOrders = orders;
    this.setData({
      stores,
      storeNames: ["全部门店", ...stores.map((s) => s.name)],
    });
    this.filter();
  },
  filter() {
    const storeId = this.data.storeIndex
      ? this.data.stores[this.data.storeIndex - 1].id
      : null;
    this.setData({
      rooms: this.allRooms
        .filter(
          (r) =>
            (!storeId || r.store_id === storeId) &&
            (this.data.actor.role !== "MANAGER" ||
              r.store_id === this.data.actor.storeId),
        )
        .map((r) => ({
          ...r,
          label: labels[r.state],
          price: money(r.price_cents),
        })),
      orders: this.allOrders
        .filter((o) => !storeId || o.store_id === storeId)
        .map((o) => ({
          ...o,
          label: labels[o.state],
          price: money(o.amount_cents),
          startText: date(o.starts_at),
          endText: date(o.ends_at),
        })),
    });
  },
  storeChange(e) {
    this.setData({ storeIndex: Number(e.detail.value) });
    this.filter();
  },
  async tabChange(e) {
    this.setData({ tab: e.currentTarget.dataset.tab, selected: null });
    if (this.data.tab === "notices") {
      try {
        this.setData({ notices: await api("/notifications") });
      } catch (err) {
        this.error(err);
      }
    }
  },
  async select(e) {
    const r = this.data.rooms.find(
      (r) => r.id === Number(e.currentTarget.dataset.id),
    );
    const packs = await api("/rooms/" + r.id + "/packages");
    this.setData({
      selected: r,
      quote: money(r.price_cents * this.data.duration),
      packages: packs,
      packageNames: [
        "按小时计价",
        ...packs.map(
          (p) =>
            p.name + " · " + p.minutes + "分钟 · ¥" + money(p.amount_cents),
        ),
      ],
      packageIndex: 0,
    });
  },
  dateChange(e) {
    this.setData({ date: e.detail.value });
  },
  timeChange(e) {
    this.setData({ time: e.detail.value });
  },
  durationChange(e) {
    const duration = Number(e.detail.value) + 1;
    this.setData({
      duration,
      durationIndex: duration - 1,
      packageIndex: 0,
      quote: money(this.data.selected.price_cents * duration),
    });
  },
  async reserve() {
    if (this.data.busy) return;
    this.setData({ busy: true });
    try {
      const parts = (this.data.date + " " + this.data.time)
        .split(/[- :]/)
        .map(Number);
      const start = new Date(
        parts[0],
        parts[1] - 1,
        parts[2],
        parts[3],
        parts[4],
      );
      await api("/bookings", "POST", {
        roomId: this.data.selected.id,
        start: start.toISOString(),
        end: new Date(
          start.getTime() + this.data.duration * 3600000,
        ).toISOString(),
        packageId: this.data.packageIndex
          ? this.data.packages[this.data.packageIndex - 1].id
          : null,
        requestKey:
          "wx_" + Date.now() + "_" + Math.random().toString(36).slice(2),
      });
      this.setData({ selected: null, tab: "orders" });
      await this.load();
      wx.showToast({ title: "预约成功", icon: "success" });
    } catch (e) {
      this.error(e);
    } finally {
      this.setData({ busy: false });
    }
  },
  async action(e) {
    if (this.data.busy) return;
    const { id, action } = e.currentTarget.dataset;
    this.setData({ busy: true });
    try {
      if (action === "extend") {
        const pending = await api("/bookings/" + id + "/extensions");
        const extension =
          pending.find(
            (x) =>
              x.state === "PENDING" && x.created_at > Date.now() - 5 * 60000,
          ) ||
          (await api("/bookings/" + id + "/extensions", "POST", {
            minutes: 60,
            requestKey:
              "ext_" + Date.now() + "_" + Math.random().toString(36).slice(2),
          }));
        await api("/extensions/" + extension.id + "/pay", "POST", {});
      } else {
        const outcome = await api("/bookings/" + id + "/" + action, "POST", {});
        if (
          (action === "open" && outcome.state !== "IN_USE") ||
          (action === "finish" && outcome.state !== "COMPLETED")
        )
          throw Error("设备未确认，请联系门店重试");
      }
      await this.load();
      wx.showToast({
        title: action === "cancel" ? "已取消，请核对退款状态" : "演示操作成功",
        icon: "none",
      });
    } catch (err) {
      this.error(err);
    } finally {
      this.setData({ busy: false });
    }
  },
  packageChange(e) {
    const index = Number(e.detail.value);
    if (!index) {
      this.setData({
        packageIndex: 0,
        quote: money(this.data.selected.price_cents * this.data.duration),
      });
      return;
    }
    const pack = this.data.packages[index - 1];
    this.setData({
      packageIndex: index,
      duration: pack.minutes / 60,
      quote: money(pack.amount_cents),
    });
  },
  inputLogin(e) {
    this.setData({ [e.currentTarget.dataset.key]: e.detail.value });
  },
  async staffLogin() {
    try {
      const r = await api("/staff/session", "POST", {
        username: this.data.username,
        password: this.data.password,
      });
      app().globalData.token = r.token;
      app().globalData.actor = r.actor;
      this.setData({ actor: r.actor, password: "" });
      await this.load();
    } catch (e) {
      this.error(e);
    }
  },
  async wechatLogin() {
    try {
      const code = await new Promise((resolve, reject) =>
        wx.login({ success: (r) => resolve(r.code), fail: reject }),
      );
      const r = await api("/wechat/session", "POST", { code });
      app().globalData.token = r.token;
      app().globalData.actor = r.actor;
      this.setData({ actor: r.actor });
      await this.load();
    } catch (e) {
      this.error(e);
    }
  },
  manage() {
    wx.navigateTo({ url: "/pages/operations/index" });
  },
  async roomAction(e) {
    if (this.data.busy) return;
    const { id, state } = e.currentTarget.dataset;
    this.setData({ busy: true });
    try {
      await api("/rooms/" + id, "PATCH", { state });
      await this.load();
    } catch (err) {
      this.error(err);
    } finally {
      this.setData({ busy: false });
    }
  },
});
