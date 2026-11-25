"use strict";
const fs = require("node:fs");
const path = require("node:path");
const crypto = require("node:crypto");
const {execFileSync} = require("node:child_process");
const {createSeed} = require("./vm-seed.cjs");
const expected = process.arch === "arm64"
  ? "3dc8370d923978d4b610fa7964d1b36ba040eefb43198809ca5bc8ac51777b18"
  : "406630c08b5e545fcc7209854b129d47e3af822db128c9f19336593b5a2d3fe0";
const [image, directory, setup] = process.argv.slice(2);
if (!image || !directory || !setup || process.getuid() !== 60000) throw new Error("VM provisioner must run as the Linux agent user with image, directory and guest setup paths");
const destination = path.resolve(directory);
if (destination !== "/home/agent/.galiais/vm") throw new Error("Unsupported VM directory");
if (fs.existsSync(destination)) {
  const state = JSON.parse(fs.readFileSync(path.join(destination,"config.json"),"utf8"));
  if (state.schemaVersion !== 1 || state.imageSha256 !== expected || fs.readFileSync(path.join(destination,".provisioned"),"utf8") !== (state.installationId??state.id)) throw new Error("An incomplete or incompatible VM directory already exists; it was not overwritten");
  console.log(JSON.stringify({state:"provisioned", directory:destination, id:state.id}));
} else {
  const available = fs.statfsSync(path.dirname(destination));
  if (available.bavail * available.bsize < 12 * 1024 ** 3) throw new Error("At least 12 GiB of free space is required");
  const digest = crypto.createHash("sha256"), input = fs.openSync(image,"r"), block = Buffer.alloc(1048576);
  try { for (let count; (count = fs.readSync(input,block,0,block.length,null)) > 0;) digest.update(block.subarray(0,count)); }
  finally { fs.closeSync(input); }
  if (digest.digest("hex") !== expected) throw new Error("Ubuntu VM image checksum mismatch");
  const temporary = fs.mkdtempSync(path.join(path.dirname(destination),"vm-install-"));
  fs.chmodSync(temporary,0o700);
  const id = crypto.randomUUID();
  try {
    fs.copyFileSync(image,path.join(temporary,"base.qcow2"),fs.constants.COPYFILE_EXCL);
    fs.chmodSync(path.join(temporary,"base.qcow2"),0o440);
    const keyDirectory = path.join(temporary,"keys");
    fs.mkdirSync(keyDirectory,{mode:0o700});
    execFileSync("/usr/bin/ssh-keygen",["-q","-t","ed25519","-N","","-C","galiais-local-vm","-f",path.join(keyDirectory,"client")],{stdio:"pipe"});
    createSeed(temporary,path.join(keyDirectory,"client.pub"),id,setup);
    execFileSync("/usr/bin/qemu-img",["create","-f","qcow2","-F","qcow2","-b","base.qcow2","system.qcow2","16G"],{cwd:temporary,stdio:"pipe"});
    fs.chmodSync(path.join(temporary,"system.qcow2"),0o600);
    fs.chmodSync(path.join(temporary,"seed.iso"),0o600);
    fs.writeFileSync(path.join(temporary,"config.json"),JSON.stringify({schemaVersion:1,id,installationId:id,bootstrapVersion:3,seedFile:"seed.iso",imageSha256:expected,memoryMiB:4096,cpus:2,sshPort:22222},null,2),{mode:0o640,flag:"wx"});
    fs.writeFileSync(path.join(temporary,".provisioned"),id,{mode:0o640,flag:"wx"});
    fs.chmodSync(temporary,0o750);
    fs.renameSync(temporary,destination);
    console.log(JSON.stringify({state:"provisioned",directory:destination,id}));
  } catch (error) {
    throw new Error("VM provisioning failed; protected partial state was retained at " + temporary,{cause:error});
  }
}
