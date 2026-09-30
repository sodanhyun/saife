package io.saife.evidence.live;

import java.io.StringReader;
import java.util.*;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;

/**
 * 공단 MSDS API XML 파서. 의존성 없이 JDK StAX만 쓴다.
 *
 * <p>응답 형태: {@code <response><header><resultCode>00</resultCode>...</header><body><items><item>...</item></items></body></response>}.
 * resultCode가 00이 아니거나 파싱이 깨지면 빈 리스트 — 호출자는 캐시로 내려간다.
 */
public final class MsdsXmlParser {
    private MsdsXmlParser() {}

    public record ChemHit(String chemId, String chemNameKor, String casNo, String unNo) {}
    public record DetailItem(String msdsItemCode, String msdsItemNameKor, String itemDetail, String lev, String upMsdsItemCode) {}

    public static List<ChemHit> parseList(String xml) {
        List<ChemHit> out = new ArrayList<>();
        for (Map<String, String> item : items(xml)) {
            String id = item.get("chemId");
            if (id != null && !id.isBlank()) {
                out.add(new ChemHit(id, item.get("chemNameKor"), item.get("casNo"), item.get("unNo")));
            }
        }
        return out;
    }

    public static List<DetailItem> parseDetail(String xml) {
        List<DetailItem> out = new ArrayList<>();
        for (Map<String, String> item : items(xml)) {
            out.add(new DetailItem(item.get("msdsItemCode"), item.get("msdsItemNameKor"),
                    item.get("itemDetail"), item.get("lev"), item.get("upMsdsItemCode")));
        }
        return out;
    }

    /** 02 항목의 '그림문자' 행에서 GHS 코드만 뽑는다. 'GHS02.gif|GHS07.gif' → 'GHS02,GHS07' */
    public static String pictogramsOf(List<DetailItem> items) {
        LinkedHashSet<String> codes = new LinkedHashSet<>();
        for (DetailItem it : items) {
            if (it.itemDetail() == null) continue;
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("GHS0[1-9]").matcher(it.itemDetail());
            while (m.find()) codes.add(m.group());
        }
        return codes.isEmpty() ? null : String.join(",", codes);
    }

    /** <item> 하나를 태그명→텍스트 맵으로. resultCode≠00이면 빈 목록 */
    private static List<Map<String, String>> items(String xml) {
        List<Map<String, String>> out = new ArrayList<>();
        if (xml == null || xml.isBlank()) return out;
        try {
            XMLInputFactory f = XMLInputFactory.newFactory();
            f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
            f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
            XMLStreamReader r = f.createXMLStreamReader(new StringReader(xml));
            Map<String, String> current = null;
            String field = null;
            StringBuilder text = new StringBuilder();
            String resultCode = null;
            boolean inResultCode = false;
            while (r.hasNext()) {
                int ev = r.next();
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    String name = r.getLocalName();
                    if ("item".equals(name)) { current = new HashMap<>(); }
                    else if (current != null) { field = name; text.setLength(0); }
                    else if ("resultCode".equals(name)) { inResultCode = true; text.setLength(0); }
                } else if (ev == XMLStreamConstants.CHARACTERS || ev == XMLStreamConstants.CDATA) {
                    if (field != null || inResultCode) text.append(r.getText());
                } else if (ev == XMLStreamConstants.END_ELEMENT) {
                    String name = r.getLocalName();
                    if ("item".equals(name) && current != null) { out.add(current); current = null; }
                    else if (current != null && name.equals(field)) { current.put(field, text.toString().trim()); field = null; }
                    else if (inResultCode && "resultCode".equals(name)) { resultCode = text.toString().trim(); inResultCode = false; }
                }
            }
            if (resultCode != null && !"00".equals(resultCode)) return List.of();
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }
}
