// Shows whether this browser is connected to ONEOFFICEAUTOMATION (the native-messaging host is running).
chrome.runtime.sendMessage({ ooa: "status" }, (s) => {
  const dot = document.getElementById("dot"), state = document.getElementById("state"), detail = document.getElementById("detail");
  if (!s) { state.textContent = "Not available"; return; }
  dot.className = "dot " + (s.connected ? "on" : "off");
  state.textContent = s.connected ? "Connected" : "Not connected";
  detail.textContent = s.connected
    ? "Agents and Agent Studio can see and automate the tabs of this browser (" + s.browser + ")."
    : "Install the connector on this computer: run  ooa browser-connector install  and restart the browser. " + (s.error || "");
});
