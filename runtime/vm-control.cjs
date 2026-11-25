"use strict";
const net = require("node:net");
const {directory,load} = require("./vm-session.cjs");
const path = require("node:path");
async function control(action) {
  load();
  const execute = {status:"query-status",shutdown:"system_powerdown"}[action];
  if (!execute) throw new Error("Unsupported VM control action");
  const socket = net.createConnection(path.join(directory,"qmp.sock"));
  let buffer = "", step = 0;
  try {
    return await new Promise((resolve,reject)=>{
      socket.setTimeout(10000,()=>socket.destroy(new Error("VM monitor timed out")));
      socket.setEncoding("utf8");
      socket.once("error",reject);
      socket.once("end",()=>reject(new Error("VM monitor closed before responding")));
      socket.on("data",chunk=>{
        buffer+=chunk;
        if(buffer.length>1048576) {socket.destroy(new Error("VM monitor response exceeded its limit"));return;}
        let end;
        while((end=buffer.indexOf("\n"))>=0) {
          const line=buffer.slice(0,end);buffer=buffer.slice(end+1);
          try {
            const message=JSON.parse(line);
            if(message.error) throw new Error(message.error.desc);
            if(message.QMP && step===0) {step=1;socket.write(JSON.stringify({execute:"qmp_capabilities",id:1})+"\n");}
            else if(message.id===1 && step===1) {step=2;socket.write(JSON.stringify({execute,id:2})+"\n");}
            else if(message.id===2 && step===2) {step=3;resolve(message.return);}
          } catch(error) {socket.destroy(error);}
        }
      });
    });
  } finally {socket.destroy();}
}
module.exports = {control};
if(require.main===module) control(process.argv[2]??"status").then(result=>console.log(JSON.stringify(result)))
  .catch(error=>{console.error(error.message);process.exitCode=1;});
