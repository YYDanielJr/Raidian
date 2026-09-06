package yy.daniel.raidian.shizuku;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Shizuku UserService 命令执行结果。
 *
 * 与 ICommandRunner.aidl 配套使用,由 AIDL 跨进程(Binder)在应用进程与
 * `:runner` 提权进程之间传递。Parcelable 的读写顺序必须与
 * {@link #writeToParcel} / {@link CommandResult(Parcel)} 保持一致。
 */
public class CommandResult implements Parcelable {
    /** 命令的 stdout+stderr 合并输出;失败时为 "ERROR: ..." 或空串 */
    public String output;
    /** 退出码;命令异常/超时为 -1 */
    public int exitCode;

    public CommandResult() {
        this.output = "";
        this.exitCode = 0;
    }

    /** 从 Parcel 恢复(读序与 writeToParcel 一致:先 output 后 exitCode) */
    protected CommandResult(Parcel in) {
        output = in.readString();
        exitCode = in.readInt();
    }

    /** 系统要求的 Parcelable.Creator */
    public static final Creator<CommandResult> CREATOR = new Creator<CommandResult>() {
        @Override
        public CommandResult createFromParcel(Parcel in) {
            return new CommandResult(in);
        }

        @Override
        public CommandResult[] newArray(int size) {
            return new CommandResult[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    /** 写入 Parcel(写序:先 output 后 exitCode,与构造函数读序对应) */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(output);
        dest.writeInt(exitCode);
    }
}
