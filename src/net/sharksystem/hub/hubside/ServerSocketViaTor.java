package net.sharksystem.hub.hubside;
import net.sf.T0rlib4j.controller.network.JavaTorRelay;
import net.sf.T0rlib4j.controller.network.TorServerSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Calendar;
import java.util.concurrent.CountDownLatch;
import java.net.*;

public class ServerSocketViaTor {
	private static final Logger LOG = LoggerFactory.getLogger(ServerSocketViaTor.class);
	private static final DateTimeFormatter dtf = DateTimeFormatter.ofPattern("HH:mm:ss");
	private static final int hiddenservicedirport = 80;
	private static final int localport = 2096;
	private static CountDownLatch serverLatch = new CountDownLatch(2);


	public static void main(String[] args) throws IOException, InterruptedException {
		File dir = new File("torfiles");

		JavaTorRelay node = new JavaTorRelay(dir);
		TorServerSocket torServerSocket = node.createHiddenService(localport, hiddenservicedirport);

		System.out.println("Hidden Service Binds to   " + torServerSocket.getHostname() + " ");
		System.out.println("Tor Service Listen to Port  " + torServerSocket.getServicePort());

		ServerSocket ssocks = torServerSocket.getServerSocket();
		Server server = new Server(ssocks);
		new Thread(server).start();

		serverLatch.await();
	}

	private static class Server implements Runnable {
		private final ServerSocket socket;
		private int count = 0;
		private static final Calendar cal = Calendar.getInstance();
		private LocalDateTime now;

		private Server(ServerSocket socket) {
			this.socket = socket;
		}

		@Override
		public void run() {

			System.out.println("Wating for incoming connections...");
			try {
				while (true) {

					Socket sock = socket.accept();
					this.now = LocalDateTime.now();
					System.out.println("Accepted Client "+ (count++) +" at Address - " +  sock.getRemoteSocketAddress()
											   + " on port " + sock.getLocalPort() + " at time " + dtf.format(now));
					ObjectInputStream in = new ObjectInputStream(sock.getInputStream());
					while(true){
						System.out.println((String) in.readObject());
					}
					//sock.close();
				}
			} catch (IOException | ClassNotFoundException e) {
				e.printStackTrace();
			}

		}
	}
}

