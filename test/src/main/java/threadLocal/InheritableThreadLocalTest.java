package threadLocal;

/**
 * InheritableThreadLocal
 * 参考：https://mp.weixin.qq.com/s?__biz=MzIzNzgyMjYxOQ==&mid=2247484380&idx=1&sn=ff2e2aaf9cfc63ae60cedd07dea26733&chksm=e8c3f428dfb47d3e5c9019d8048de1be45a1f0a40ccb5e4566e38f5988e7c6ba085a5885fcdd&mpshare=1&scene=24&srcid=0508dPfcozaNCAVZrjBceqli&sharer_sharetime=1589006234715&sharer_shareid=cded50ac01d784d35c8ca2a1912ee86e#rd
 */
public class InheritableThreadLocalTest {
    private static InheritableThreadLocal<Integer> requestIdThreadLocal = new InheritableThreadLocal<>();

    /**
     * 在子线程中如愿访问到了在主线程中设置的本地环境变量,
     * 但是：线程池复用线程情况下就失去意义了
     * @param args
     */
    public static void main(String[] args) {
        Integer reqId = new Integer(5);
        InheritableThreadLocalTest a = new InheritableThreadLocalTest();
        a.setRequestId(reqId);
    }

    public void setRequestId(Integer requestId) {
        requestIdThreadLocal.set(requestId);
        doBussiness();
    }

    public void doBussiness() {
        System.out.println("首先打印requestId:" + requestIdThreadLocal.get());
        (new Thread(new Runnable() {
            @Override
            public void run() {
                System.out.println("子线程启动");
                System.out.println("在子线程中访问requestId:" + requestIdThreadLocal.get());
            }
        })).start();
    }
}