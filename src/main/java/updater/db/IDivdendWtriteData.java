package updater.db;

public interface IDivdendWtriteData {

    public int getDividendDate();  // 除权除息日

    public float getFactor(float price);

    public String getText();

}
