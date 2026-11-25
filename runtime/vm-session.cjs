"use strict";
const fs = require("node:fs");
const path = require("node:path");
const {spawn} = require("node:child_process");
const directory = "/home/agent/.galiais/vm";
function load() {
  if (process.getuid() !== 60000) throw new Error("VM connection must run as the Linux agent user");
  const config = JSON.parse(fs.readFileSync(path.join(directory,"config.json"),"utf8"));
  if (config.schemaVersion !== 1 || !/^[a-f0-9-]{36}$/.test(config.id) ||
      config.sshPort !== 22222 || !Number.isInteger(config.memoryMiB) || config.memoryMiB < 2048 || config.memoryMiB > 16384 ||
      !Number.isInteger(config.cpus) || config.cpus < 1 || config.cpus > 8 ||
      fs.readFileSync(path.join(directory,".provisioned"),"utf8") !== (config.installationId??config.id) ||
      (config.seedFile !== undefined && config.seedFile !== "seed.iso" && !/^seed-[a-f0-9-]{36}\.iso$/.test(config.seedFile))) throw new Error("Invalid VM configuration");
  return config;
}
function trust(id, publicKey) {
  const config = load();
  if (id !== config.id || !/^ssh-ed25519 [A-Za-z0-9+/]{64,128}={0,2}$/.test(publicKey)) throw new Error("Invalid VM host identity");
  const target = path.join(directory,"known_hosts");
  const entry = `[127.0.0.1]:${config.sshPort} ${publicKey}\n`;
  if (fs.existsSync(target)) {
    if (fs.readFileSync(target,"utf8") !== entry) throw new Error("VM host key changed; existing trust was not overwritten");
  } else fs.writeFileSync(target,entry,{mode:0o600,flag:"wx"});
}
const quote = value => "'" + value.replaceAll("'","'\\''") + "'";
function runEngine(executable, args, stdio) {
  const config = load();
  if (!fs.existsSync(path.join(directory,"known_hosts"))) throw new Error("VM host identity has not been established by the owned console");
  const command = "cd /home/agent/workspace && exec " + quote(executable) + " " + args.map(quote).join(" ");
  return spawn("/usr/bin/ssh",["-F","/dev/null","-i",path.join(directory,"keys/client"),"-T",
    "-o","IdentitiesOnly=yes","-o","BatchMode=yes","-o","StrictHostKeyChecking=yes",
    "-o","UserKnownHostsFile="+path.join(directory,"known_hosts"),"-o","GlobalKnownHostsFile=/dev/null",
    "-o","ConnectTimeout=15","-o","ServerAliveInterval=15","-o","ServerAliveCountMax=3",
    "-o","ClearAllForwardings=yes","-p",String(config.sshPort),"--","agent@127.0.0.1",command],{stdio});
}
function runCodex(args, stdio = "inherit") {return runEngine("/opt/galiais-codex/usr/lib/chatgpt/resources/codex",args,stdio);}
function runClaude(args, stdio = "inherit") {return runEngine("/opt/galiais-codex/claude/2.1.323/claude",args,stdio);}
module.exports = {directory,load,trust,runCodex,runClaude};
if (require.main === module) {
  const [mode,...args] = process.argv.slice(2);
  if (mode === "trust") { if (args.length !== 2) throw new Error("Invalid host enrollment arguments"); trust(args[0],args[1]); }
  else {
    if (!["app-server","codex","claude","claude-sdk","provider-gateway","agent-hub"].includes(mode)) throw new Error("Unsupported VM command");
    if ((mode === "claude-sdk" || mode === "provider-gateway" || mode === "agent-hub" || mode === "app-server") && args.length) throw new Error("Bridge process does not accept shell arguments");
    const nodeBin = "/opt/galiais-codex/usr/lib/chatgpt/resources/node";
    const mux = "/opt/galiais-codex/android/guest-mux.mjs";
    const child = mode === "app-server" ? runEngine(nodeBin,[mux,"codex"],"inherit") :
      mode === "claude-sdk" ? runEngine(nodeBin,[mux,"claude"],"inherit") :
      mode === "provider-gateway" ? runEngine(nodeBin,[mux,"gateway"],"inherit") :
      mode === "agent-hub" ? runEngine(nodeBin,[mux,"agent-hub"],"inherit") :
      mode === "claude" ? runClaude(args) : runCodex(args);
    child.once("error",error=>{ console.error(error.message); process.exitCode=1; });
    child.once("exit",(code,signal)=>{ process.exitCode=code??1; if (signal) console.error("VM connection ended by signal " + signal); });
    for (const signal of ["SIGTERM","SIGINT"]) process.once(signal,()=>child.kill(signal));
  }
}
