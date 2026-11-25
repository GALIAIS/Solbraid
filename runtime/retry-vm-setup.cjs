"use strict";
const fs = require("node:fs");
const path = require("node:path");
const net = require("node:net");
const crypto = require("node:crypto");
const {load,directory} = require("./vm-session.cjs");
const {createSeed} = require("./vm-seed.cjs");
async function retry(setup, upgradeOnly) {
  const config=load();
  if(upgradeOnly && config.bootstrapVersion===3) return;
  const enrolled=fs.existsSync(path.join(directory,"known_hosts"));
  if(!setup || (!upgradeOnly && enrolled)) throw new Error("Automatic setup recovery is only available before the VM host identity is enrolled");
  const socketPath=path.join(directory,"qmp.sock");
  if(fs.existsSync(socketPath)) await new Promise((resolve,reject)=>{
    const socket=net.createConnection(socketPath);
    socket.setTimeout(3000,()=>socket.destroy(new Error("VM monitor did not respond")));
    socket.once("connect",()=>{socket.destroy();reject(new Error("Shut down the VM before retrying setup"));});
    socket.once("error",error=>["ENOENT","ECONNREFUSED"].includes(error.code)?resolve():reject(error));
  });
  const stage=fs.mkdtempSync(path.join(directory,"seed-recovery-"));
  fs.chmodSync(stage,0o700);
  const id=crypto.randomUUID(), seedFile=`seed-${id}.iso`;
  // 已建立信任的虚拟机只升级配置，保留客户机主机密钥；下一次启动仍必须匹配原 known_hosts。
  createSeed(stage,path.join(directory,"keys/client.pub"),id,setup,{preserveHostKey:enrolled});
  fs.renameSync(path.join(stage,"seed.iso"),path.join(directory,seedFile));
  function sync(file) {const fd=fs.openSync(file,"r");try{fs.fsyncSync(fd);}finally{fs.closeSync(fd);}}
  sync(path.join(directory,seedFile));sync(directory);
  const updated={...config,id,installationId:config.installationId??config.id,bootstrapVersion:3,seedFile};
  const next=path.join(stage,"config.json");
  fs.writeFileSync(next,JSON.stringify(updated,null,2),{mode:0o640,flag:"wx"});
  const descriptor=fs.openSync(next,"r");try{fs.fsyncSync(descriptor);}finally{fs.closeSync(descriptor);}
  fs.copyFileSync(path.join(directory,"config.json"),path.join(stage,"previous-config.json"),fs.constants.COPYFILE_EXCL);
  // 单独替换引用新 seed 的配置，断电时旧磁盘与旧配置仍可恢复，不重建用户磁盘。
  fs.renameSync(next,path.join(directory,"config.json"));
  sync(directory);
  console.log(JSON.stringify({state:"setup-retry-prepared",id}));
}
const upgradeOnly=process.argv[2]==="--upgrade-bootstrap";
retry(process.argv[upgradeOnly?3:2],upgradeOnly).catch(error=>{console.error(error.message);process.exitCode=1;});
