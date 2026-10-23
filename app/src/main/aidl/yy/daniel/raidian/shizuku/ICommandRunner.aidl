package yy.daniel.raidian.shizuku;

import yy.daniel.raidian.shizuku.CommandResult;

interface ICommandRunner {
    String executeCommand(String command);
    int executeCommandWithExitCode(String command);
    CommandResult executeCommandWithResult(String command);
    // 在提权进程内直接遍历读取热区(无 shell 子进程开销)
    String readThermalZones();
    void destroy();
    String readHardwareSnapshot();
}
