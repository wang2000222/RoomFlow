module.exports = function api(path, method = "GET", data) {
  return new Promise((resolve, reject) =>
    wx.request({
      url: getApp().globalData.baseUrl + "/api" + path,
      method,
      data,
      header: {
        "Content-Type": "application/json",
        Authorization: "Bearer " + getApp().globalData.token,
      },
      success: (r) => {
        if (r.statusCode >= 200 && r.statusCode < 300) resolve(r.data);
        else {
          if (r.statusCode === 401) {
            getApp().globalData.token = "";
            getApp().globalData.actor = null;
          }
          reject(Error(r.data.message || "操作失败"));
        }
      },
      fail: () => reject(Error("无法连接服务，请检查服务器地址")),
    }),
  );
};
