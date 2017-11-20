package org.kohsuke.file_leak_detector;

import com.sun.tools.attach.VirtualMachine;
import sun.nio.ch.IOUtil;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.channels.Pipe;
import java.util.Scanner;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * @author Kohsuke Kawaguchi
 */
public class PipeDemo {

    public static void attachGivenAgentToThisVM(String pathToAgentJar,String arg) {
        try {
            String nameOfRunningVM = ManagementFactory.getRuntimeMXBean().getName();
            String pid = nameOfRunningVM.substring(0, nameOfRunningVM.indexOf('@'));
            VirtualMachine vm = VirtualMachine.attach(pid);
            vm.loadAgent(pathToAgentJar, arg);
            vm.detach();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void main(String[] args) throws IOException {
        attachGivenAgentToThisVM(args[0],args[1]);
        final Pipe s = Pipe.open();
        s.sink().close();
        //s.source().close();
        System.out.println("Dumping the table");
        Listener.dump(System.out);

        System.out.println("done");

    }
}
