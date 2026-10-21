// Headless peer for JVM interoperability tests. Never used by the packaged UI.
const { Engine } = require("../engine.cjs");
const engine = new Engine({
  name: "Windows engine test",
  folder: process.argv[3],
  event: (type, data) => {
    if (type === "consent") engine.approve();
    if (type === "error") {
      console.error(data);
      engine.close();
      process.exitCode = 1;
    }
    if (type === "done") {
      console.log(JSON.stringify({ type, ...data }));
      setTimeout(() => {
        engine.close();
      }, 150);
    }
  },
});
engine
  .start()
  .then((port) => {
    if (process.argv[2] === "send") engine.stageText("Interop payload 🌿");
    console.log(JSON.stringify({ port }));
  })
  .catch((e) => {
    console.error(e);
    process.exit(1);
  });
