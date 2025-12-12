package Task2;

import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

public class App {
    public static void main(String[] args) throws InterruptedException {
        List<Thread> threads = new ArrayList<>();
        int numThreads = 3;
        SharedData sharedData = new SharedData();
        Scanner input = new Scanner(System.in);
        System.out.print("Enter the maximum count value: ");
        int maxCount = input.nextInt();
        input.nextLine();
        input.close();
        for (int i = 0; i < numThreads; i++) {
            final int threadId = i;

            Thread t = new Thread(() -> {
                try {
                    while (true) {
                        synchronized (sharedData) {
                            if (sharedData.getCurrentNum() == 0) {
                                sharedData.notifyAll();
                                break;
                            }
                            while (sharedData.getCurrentThreadIndexTurn() != threadId) {
                                sharedData.wait();
                                if (sharedData.getCurrentNum() == 0) {
                                    break;
                                }
                            }
                            if (sharedData.getCurrentNum() > maxCount) {
                                break;
                            }
                            if (sharedData.getCurrentNum() == 0) {
                                break;
                            }
                            System.out.println("Thread" + (threadId + 1) + ": " + sharedData.getCurrentNum());
                            sharedData.updateCurrentNum(maxCount);
                            int nextThreadID = (sharedData.getCurrentThreadIndexTurn() + 1) % numThreads;
                            sharedData.updateThreadIndexTurn(nextThreadID);
                            sharedData.notifyAll();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "Thread-" + i);

            threads.add(t);
        }
        for (Thread t : threads) {
            t.start();
        }
        for (Thread t : threads) {
            t.join();
        }
    }
}
