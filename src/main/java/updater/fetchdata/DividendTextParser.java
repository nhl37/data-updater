package updater.fetchdata;

public class DividendTextParser {
    private static int numberIndex(String text, int start) {
        int curch;
        for(int i=start; i<start+8; i++) {
            curch = text.charAt(i);
            if(curch >= '0' && curch <= '9') {
                return i;
            } else {
                continue;
            }
        }
        throw new RuntimeException("TODO error data");
    }
    private static int numberEnd(String text, int start) {
        int curch;
        int count = text.length();
        for(int i=start; i<count; i++) {
            curch = text.charAt(i);
            if(curch >= '0' && curch <= '9') {
                continue;
            } else if(curch == '.'){
                continue;
            }else {
                return i;
            }
        }
        return count;
    }

    public static void main(String[] args) {
        DividendData dividendData = new DividendData();
        parse(dividendData,"10转10");
    }
    public static void parse(DividendData dividendData, String text) {
        //（送股）10转增3.000股 派2.000元 /10转增1.000股 派5.150元 /10送3.000股 转增7.000股 派2.000元
        // (10配3.000股 配股价8.750元)
        //（分红）(10派1.800元)
        char ch;
        int numberStartIndex = 0;
        int numberEndIndex = 0;
        int tmpNextCh;
        String valueText;
        int startIndex = 0;
        if(text.startsWith("10每股")) {
            startIndex = 4;
        } else if(text.startsWith("10股")) {
            startIndex = 3;
        } else if(text.startsWith("10")) {
            startIndex = 2;
        }
        for (int i = startIndex; i < text.length(); i++) {
            ch = text.charAt(i);
            switch (ch) {

                case '派'://分红
                    numberStartIndex = numberIndex(text,i+1);
                    numberEndIndex = numberEnd(text,numberStartIndex);
                    valueText = text.substring(numberStartIndex, numberEndIndex);
                    dividendData.cashDividend = Float.valueOf(valueText)/10;
                    i= numberEndIndex-1;
                    break;
                case '转':
                case '增':
                    numberStartIndex = numberIndex(text,i+1);
                    numberEndIndex = numberEnd(text,numberStartIndex);
                    valueText = text.substring(numberStartIndex, numberEndIndex);
                    // 累加送转增股数（per 10股），支持"送2转增2"等复合操作
                    dividendData.stockGiftCount += Float.parseFloat(valueText);
                    i= numberEndIndex-1;
                    break;
                case '每'://每股
                    tmpNextCh = text.charAt(i+1);
                    if(tmpNextCh == '股') {
                        i++;
                        continue;
                    }
                    throw new RuntimeException("TODO");
                case '拆'://拆股 (保留原有逻辑，使用bonusShares字段)
                    numberStartIndex = numberIndex(text,i+1);
                    numberEndIndex = numberEnd(text,numberStartIndex);
                    valueText = text.substring(numberStartIndex, numberEndIndex);
                    dividendData.bonusShares = Float.parseFloat(valueText);
                    i= numberEndIndex-1;
                    break;
                case '送':
                    numberStartIndex = numberIndex(text,i+1);
                    numberEndIndex = numberEnd(text,numberStartIndex);
                    valueText = text.substring(numberStartIndex, numberEndIndex);
                    // 累加送转增股数（per 10股），支持"送2转增2"等复合操作
                    dividendData.stockGiftCount += Float.parseFloat(valueText);
                    i= numberEndIndex-1;
                    break;
                case '配':
                    tmpNextCh = text.charAt(i+1);
                    int nextnextch = text.charAt(i+2);
                    if(tmpNextCh == '股'
                            && nextnextch=='价') {//配股价
                        numberStartIndex = numberIndex(text,i+1);
                        numberEndIndex = numberEnd(text,numberStartIndex);
                        valueText = text.substring(numberStartIndex, numberEndIndex);
                        dividendData.allotmentPrice = Float.parseFloat(valueText);
                    } else {//配股比例
                        numberStartIndex = numberIndex(text,i+1);
                        numberEndIndex = numberEnd(text,numberStartIndex);
                        valueText = text.substring(numberStartIndex, numberEndIndex);
                        dividendData.allotment = Float.parseFloat(valueText);
                    }
                    i= numberEndIndex-1;
                    break;
                case '缩'://缩股 (保留原有逻辑，使用bonusShares字段)
                    numberStartIndex = numberIndex(text,i+1);
                    numberEndIndex = numberEnd(text,numberStartIndex);
                    valueText = text.substring(numberStartIndex, numberEndIndex);
                    dividendData.bonusShares = Float.parseFloat(valueText)/10;
                    i= numberEndIndex-1;
                    break;
                case '元':
                    break;
                case '股':
                case ' ':
                    break;

                default:
                    throw new RuntimeException("TODO:'" + ch +"' :" +text);

            }


        }

    }
}