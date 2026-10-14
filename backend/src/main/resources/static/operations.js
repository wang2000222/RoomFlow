function clockMinute(n) {
  return (
    String(Math.floor(n / 60)).padStart(2, "0") +
    ":" +
    String(n % 60).padStart(2, "0")
  );
}
const panelTabs = ["config", "staff", "finance", "notifications"];
let configuration = { stores: [], rooms: [], packages: [] },
  staffRows = [],
  bookingPackages = [];
const operationDialog = document.createElement("dialog");
operationDialog.id = "operation-dialog";
document.body.append(operationDialog);
function formDialog(title, fields, submit) {
  operationDialog.innerHTML = `<form><div class="modal-title"><h2>${esc(title)}</h2><button type="button" data-close-operation>×</button></div>${fields.map((f) => `<label>${esc(f.label)}${f.type === "select" ? `<select name="${f.key}">${f.options.map((o) => `<option value="${esc(o.value)}" ${String(o.value) === String(f.value) ? "selected" : ""}>${esc(o.label)}</option>`).join("")}</select>` : `<input name="${f.key}" type="${f.type || "text"}" value="${esc(f.value ?? "")}" ${f.required === false ? "" : "required"} ${f.type === "number" ? 'step="any"' : ""}>`}</label>`).join("")}<button class="primary">保存 / 确认</button></form>`;
  operationDialog.querySelector("[data-close-operation]").onclick = () =>
    operationDialog.close();
  operationDialog.querySelector("form").onsubmit = async (e) => {
    e.preventDefault();
    const button = e.target.querySelector(".primary");
    button.disabled = true;
    const data = {};
    fields.forEach((f) => {
      const value = e.target.elements[f.key].value;
      data[f.key] =
        f.type === "number"
          ? f.money
            ? Math.round(Number(value) * 100)
            : Number(value)
          : f.clock
            ? Number(value.split(":")[0]) * 60 + Number(value.split(":")[1])
            : f.key === "enabled"
              ? value === "true"
              : value;
    });
    try {
      await submit(data);
      operationDialog.close();
      await load();
      await loadPanel();
      toast("已保存");
    } catch (err) {
      toast(err.message);
    } finally {
      button.disabled = false;
    }
  };
  operationDialog.showModal();
}
const enabledField = (value) => ({
  key: "enabled",
  label: "经营状态",
  type: "select",
  value: value !== false,
  options: [
    { value: true, label: "启用" },
    { value: false, label: "停用 / 下架" },
  ],
});
const selectStore = (value) => ({
  key: "storeId",
  label: "所属门店",
  type: "select",
  value,
  options: configuration.stores.map((s) => ({ value: s.id, label: s.name })),
});
function editStore(id) {
  const s = configuration.stores.find((s) => s.id === Number(id)) || {};
  formDialog(
    id ? "编辑门店" : "新增门店",
    [
      { key: "name", label: "门店名称", value: s.name },
      { key: "address", label: "门店地址", value: s.address },
      {
        key: "openMinute",
        label: "营业开始（如 10:00）",
        clock: true,
        value: clockMinute(s.open_minute ?? 0),
      },
      {
        key: "closeMinute",
        label: "营业结束（24:00 表示零点）",
        clock: true,
        value: clockMinute(s.close_minute ?? 1440),
      },
      {
        key: "cleaningMinutes",
        label: "每单结束后保洁缓冲（分钟）",
        type: "number",
        value: s.cleaning_minutes ?? 15,
      },
      {
        key: "earlyMinutes",
        label: "允许提前入场（分钟，也会占用预约时段）",
        type: "number",
        value: s.early_minutes ?? 0,
      },
      {
        key: "cancelMinutes",
        label: "开始前多少分钟截止自助取消",
        type: "number",
        value: s.cancel_minutes ?? 60,
      },
      enabledField(s.enabled),
    ],
    (b) => api("/stores" + (id ? "/" + id : ""), id ? "PUT" : "POST", b),
  );
}
function editRoom(id) {
  const r = configuration.rooms.find((r) => r.id === Number(id)) || {};
  formDialog(
    id ? "编辑包间" : "新增包间",
    [
      { key: "name", label: "包间名称", value: r.name },
      selectStore(r.store_id ?? configuration.stores[0]?.id),
      {
        key: "capacity",
        label: "可容纳人数",
        type: "number",
        value: r.capacity ?? 4,
      },
      {
        key: "priceCents",
        label: "每小时价格（元）",
        type: "number",
        value: (r.price_cents ?? 3800) / 100,
        money: true,
      },
      enabledField(r.enabled),
    ],
    (b) =>
      api("/rooms" + (id ? "/" + id : ""), id ? "PUT" : "POST", {
        ...b,
        storeId: Number(b.storeId),
      }),
  );
}
function editPackage(id) {
  const p = configuration.packages.find((p) => p.id === id) || {};
  formDialog(
    id ? "编辑套餐" : "新增套餐",
    [
      { key: "name", label: "套餐名称", value: p.name },
      {
        key: "roomId",
        label: "所属包间",
        type: "select",
        value: p.room_id ?? configuration.rooms[0]?.id,
        options: configuration.rooms.map((r) => ({
          value: r.id,
          label: r.name,
        })),
      },
      {
        key: "minutes",
        label: "套餐时长（分钟，半小时递增）",
        type: "number",
        value: p.minutes ?? 120,
      },
      {
        key: "amountCents",
        label: "套餐价格（元）",
        type: "number",
        value: (p.amount_cents ?? 6000) / 100,
        money: true,
      },
      enabledField(p.enabled),
    ],
    (b) =>
      api("/packages", "POST", {
        ...b,
        roomId: Number(b.roomId),
        ...(id ? { id } : {}),
      }),
  );
}
function editStaff(id) {
  const s = staffRows.find((s) => s.id === id) || {};
  formDialog(
    id ? "编辑员工 / 重置密码" : "新增门店管理员",
    [
      { key: "username", label: "登录账号", value: s.username },
      {
        key: "password",
        label: id ? "新密码（留空保持，至少12位）" : "初始密码（至少12位）",
        type: "password",
        required: !id,
      },
      selectStore(s.store_id ?? configuration.stores[0]?.id),
      enabledField(s.enabled),
    ],
    (b) =>
      api("/staff", "POST", {
        ...b,
        role: "MANAGER",
        storeId: Number(b.storeId),
        ...(id ? { id } : {}),
      }),
  );
}
async function openOrderOperation(id, action) {
  const order = orders.find((o) => o.id === id);
  if (action === "remark")
    return formDialog(
      "订单备注",
      [
        {
          key: "remark",
          label: "备注（最多255字）",
          value: order.remark,
          required: false,
        },
      ],
      (b) => api("/bookings/" + id + "/remark", "PATCH", b),
    );
  formDialog(
    "续时：先锁定时段，再演示支付",
    [
      {
        key: "minutes",
        label: "增加时长（分钟，半小时递增）",
        type: "number",
        value: 60,
      },
    ],
    async (b) => {
      const pending = await api("/bookings/" + id + "/extensions");
      const extension =
        pending.find(
          (e) => e.state === "PENDING" && e.created_at > Date.now() - 5 * 60000,
        ) ||
        (await api("/bookings/" + id + "/extensions", "POST", {
          minutes: b.minutes,
          requestKey: crypto.randomUUID(),
        }));
      await api("/extensions/" + extension.id + "/pay", "POST", {});
      toast("续时已支付并生效");
    },
  );
}
async function loadBookingPackages(room) {
  bookingPackages = await api("/rooms/" + room + "/packages");
  $("booking-package").innerHTML =
    '<option value="">按小时计价</option>' +
    bookingPackages
      .map(
        (p) =>
          `<option value="${p.id}">${esc(p.name)} · ${p.minutes}分钟 · ${money(p.amount_cents)}</option>`,
      )
      .join("");
  $("booking-package").onchange = () => {
    const p = bookingPackages.find((p) => p.id === $("booking-package").value);
    if (p) {
      const hours = p.minutes / 60;
      let option = Array.from($("duration").options).find(
        (o) => Number(o.value) === hours,
      );
      if (!option) {
        option = new Option(hours + " 小时", hours);
        $("duration").add(option);
      }
      $("duration").value = hours;
      $("price-preview").textContent = "预约金额：" + money(p.amount_cents);
    } else preview();
  };
  $("duration").onchange = () => {
    $("booking-package").value = "";
    preview();
  };
}
async function loadPanel() {
  if (!actor) return;
  document.querySelector('[data-tab="config"]').hidden = actor.role !== "OWNER";
  document.querySelector('[data-tab="staff"]').hidden = actor.role !== "OWNER";
  document.querySelector('[data-tab="finance"]').hidden = actor.role === "USER";
  if (!panelTabs.includes(activeTab)) return;
  try {
    if (activeTab === "config") {
      configuration = await api("/configuration");
      $("config").innerHTML =
        `<div class="login-actions"><button data-edit-store="">新增门店</button><button data-edit-room="">新增包间</button><button data-edit-package="">新增计价套餐</button></div><h3>门店</h3>${configuration.stores.map((s) => `<div class="room-content"><strong>${esc(s.name)}</strong> · 保洁${s.cleaning_minutes}分钟 · 提前入场${s.early_minutes}分钟 · ${s.enabled ? "营业" : "停用"} <button data-edit-store="${s.id}">编辑</button></div>`).join("")}<h3>包间</h3>${configuration.rooms.map((r) => `<div class="room-content">${esc(r.name)} · ${money(r.price_cents)}/小时 · ${r.enabled ? "上架" : "下架"} <button data-edit-room="${r.id}">编辑</button></div>`).join("")}<h3>套餐</h3>${configuration.packages.map((p) => `<div class="room-content">${esc(p.name)} · ${p.minutes}分钟 · ${money(p.amount_cents)} · ${p.enabled ? "启用" : "停用"} <button data-edit-package="${p.id}">编辑</button></div>`).join("")}`;
    }
    if (activeTab === "staff") {
      [configuration, staffRows] = await Promise.all([
        api("/configuration"),
        api("/staff"),
      ]);
      $("staff").innerHTML =
        `<button data-edit-staff="">新增员工</button><p>停用、改店或重置密码会撤销该员工现有登录。管理员不能通过此处创建负责人账号。</p>${staffRows.map((s) => `<div class="room-content">${esc(s.username)} · ${esc(s.role)} · 门店${s.store_id ?? "全部"} · ${s.enabled ? "启用" : "停用"} ${s.role === "MANAGER" ? `<button data-edit-staff="${s.id}">编辑 / 停用</button>` : ""}</div>`).join("")}`;
    }
    if (activeTab === "finance") {
      const f = await api("/finance");
      const received = f.payments
          .filter((p) => p.state === "SUCCESS")
          .reduce((n, p) => n + p.amount_cents, 0),
        refunded = f.refunds
          .filter((r) => r.state === "SUCCESS")
          .reduce((n, r) => n + r.amount_cents, 0);
      $("finance").innerHTML =
        `<div class="notice">模拟收款 ${money(received)} / 模拟退款 ${money(refunded)} / 净额 ${money(received - refunded)}。不是微信实际结算数据。</div><div class="login-actions">${actor.role === "OWNER" ? "<button data-reconcile>模拟退款对账 / 重试</button><button data-run-jobs>运行到时检查</button><button data-fault>模拟一次设备或退款故障</button>" : ""}</div><h3>设备告警</h3>${f.alerts.map((d) => `<div class="notice">包间${d.room_id} · ${esc(d.error)} · 预约${esc(d.booking_id)}</div>`).join("") || "<p>暂无失败设备指令</p>"}<h3>收款流水</h3>${f.payments.map((p) => `<div class="room-content">${esc(p.booking_id)} · ${p.kind} · ${money(p.amount_cents)} · ${labels[p.state] || p.state}</div>`).join("")}<h3>退款流水</h3>${f.refunds.map((r) => `<div class="room-content">${esc(r.booking_id)} · ${money(r.amount_cents)} · ${labels[r.state] || r.state} · 尝试${r.attempts}次 ${r.state === "FAILED" ? `<button data-refund-retry="${r.id}">重试退款</button>` : ""}</div>`).join("")}<h3>设备指令与回执</h3>${f.commands.map((d) => `<div class="room-content">包间${d.room_id} · ${d.action} · ${labels[d.state] || d.state} · 尝试${d.attempts}次</div>`).join("")}`;
    }
    if (activeTab === "notifications") {
      const notes = await api("/notifications");
      $("notifications").innerHTML =
        notes
          .map(
            (n) =>
              `<div class="notice">${date(n.created_at)} · ${esc(n.message)}<br><span class="small">${esc(n.booking_id)}</span></div>`,
          )
          .join("") ||
        '<div class="empty">暂无消息。到时前10分钟生成站内提醒。</div>';
    }
  } catch (e) {
    toast(e.message);
  }
}
document.addEventListener("click", async (e) => {
  const b = e.target.closest("button");
  if (!b) return;
  try {
    if (b.dataset.tab) {
      await loadPanel();
      return;
    }
    if ("editStore" in b.dataset) return editStore(b.dataset.editStore);
    if ("editRoom" in b.dataset) return editRoom(b.dataset.editRoom);
    if ("editPackage" in b.dataset) return editPackage(b.dataset.editPackage);
    if ("editStaff" in b.dataset) return editStaff(b.dataset.editStaff);
    if ("fault" in b.dataset)
      return formDialog(
        "模拟故障",
        [
          {
            key: "key",
            label: "故障目标，如 device:1:OPEN 或 refund:订单编号",
            value: "device:1:OPEN",
          },
          { key: "count", label: "失败次数（0至5）", type: "number", value: 1 },
        ],
        (d) => api("/simulation/fault", "POST", d),
      );
    if ("refundRetry" in b.dataset) {
      await api("/refunds/" + b.dataset.refundRetry + "/retry", "POST", {});
      await loadPanel();
    }
    if ("reconcile" in b.dataset) {
      await api("/reconcile", "POST", {});
      await loadPanel();
    }
    if ("runJobs" in b.dataset) {
      await api("/jobs/run", "POST", {});
      await load();
      await loadPanel();
    }
  } catch (err) {
    toast(err.message);
  }
});
const originalLoad = load;
load = async function () {
  await originalLoad();
  await loadPanel();
};
const staffLogin = document.createElement("div");
staffLogin.className = "login-actions";
staffLogin.innerHTML = '<button id="staff-sign-in">员工账号登录</button>';
$("login").querySelector(".login-card").append(staffLogin);
$("staff-sign-in").onclick = () =>
  formDialog(
    "员工账号登录",
    [
      { key: "username", label: "账号" },
      { key: "password", label: "密码", type: "password" },
    ],
    async (b) => {
      const session = await api("/staff/session", "POST", b);
      token = session.token;
      actor = session.actor;
      sessionStorage.setItem("roomflow-token", token);
      sessionStorage.setItem("roomflow-actor", JSON.stringify(actor));
      await load();
    },
  );
api("/capabilities")
  .then((c) => {
    if (!c.demo)
      document
        .querySelectorAll("[data-account]")
        .forEach((b) => (b.hidden = true));
  })
  .catch(() => {});
