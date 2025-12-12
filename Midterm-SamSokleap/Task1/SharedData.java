package Task1;

public class SharedData {
    private int currentNum = 1;
    private int threadIndexTurn = 0;
    private boolean isCountingUp = true;

    public int getCurrentNum(){
        return currentNum;
    }

    public int getCurrentThreadIndexTurn(){
        return threadIndexTurn;
    }

    public void updateCurrentNum(int maxCount){
        if (isCountingUp) {
            this.currentNum++;
            if (this.currentNum >= maxCount) {
                this.currentNum = maxCount; 
                this.isCountingUp = false;
            }
        } else {
            this.currentNum--;
        }
    }

    public void updateThreadIndexTurn(int newThreadIndexTurn){
        this.threadIndexTurn = newThreadIndexTurn;
    }
}
