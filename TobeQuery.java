import java.sql.*;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

public class TobeQuery {

    private static final String URL = "jdbc:oracle:thin:@localhost:1521:orcl";
    private static final String USER = "C##user1";
    private static final String PASSWORD = "1234";

    private static final String SOURCE_SQL = "test";

    public static void main(String[] args) throws IOException {
        Path baseDir = Paths.get("").toAbsolutePath();
        Path sourcePath = baseDir.resolve(SOURCE_SQL+".sql");
        Path outputPath = baseDir.resolve(SOURCE_SQL+"_tobe.sql");

        // TOBE 테이블에서 매핑 정보 읽기
        // 컬럼 순서: 원본테이블명, 수정테이블명, 원본엔터티명, 수정엔터티명, 원본컬럼명, 수정컬럼명, 원본속성명, 수정속성명
        List<String[]> mappings = new ArrayList<>();

        try (Connection conn = DriverManager.getConnection(URL, USER, PASSWORD);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "WITH M AS ( " +
                     "  SELECT A.* " +
                     "       , COUNT(DISTINCT 수정컬럼명) OVER (PARTITION BY 원본컬럼명) AS CNT " +
                     "  FROM TOBE A " +
                     "  WHERE 원본테이블명 NOT IN ('TBEM_SRVY_RSLT','TVAD_REDSATCNHI','TVAB_DSINCO','TVAB_BUMA','AUTHOR_MENU') /* 설문결과,재파견여부확인이력,파견기관코드,사업마스터, 권한메뉴*/" +
                     //"  WHERE 원본컬럼명 IN ('RCRIT_GRNU')" +
                     ") " +
                     "SELECT M.원본테이블명 " +
                     "     , NVL(M.수정테이블명, M.원본테이블명||'(X)') AS 수정테이블명 " +
                     "     , M.원본엔터티명 " +
                     "     , NVL(M.수정엔터티명, M.원본엔터티명||'(X)') AS 수정엔터티명 " +
                     "     , M.원본컬럼명 " +
                     "     , (CASE WHEN M.CNT > 1 " +
                     "             THEN NVL(M.수정컬럼명, M.원본컬럼명||'(X)')||'(중복'||M.CNT||')' " +
                     "             ELSE NVL(M.수정컬럼명, M.원본컬럼명||'(X)') " +
                     "        END) AS 수정컬럼명 " +
                     "     , (LOWER(SUBSTR(REPLACE(INITCAP(M.원본컬럼명),'_'),1,1))" +
                     "       ||SUBSTR(REPLACE(INITCAP(M.원본컬럼명),'_'),2)) AS 원본컬럼명_카멜 " +
                     "     , NVL((LOWER(SUBSTR(REPLACE(INITCAP(M.수정컬럼명),'_'),1,1))" +
                     "       ||SUBSTR(REPLACE(INITCAP(M.수정컬럼명),'_'),2)),'(X)') AS 수정컬럼명_카멜 " +
                     "     , M.원본속성명 " +
                     "     , NVL(M.수정속성명, M.원본속성명||'(X)') AS 수정속성명 " +
                     "     , M.CNT " +
                     "FROM M " +
                     "ORDER BY M.원본테이블명")) {

            while (rs.next()) {
                mappings.add(new String[]{
                    rs.getString("원본테이블명"),    rs.getString("수정테이블명"),    // [0][1] 테이블명
                    rs.getString("원본엔터티명"),    rs.getString("수정엔터티명"),    // [2][3] 엔터티명
                    rs.getString("원본컬럼명"),      rs.getString("수정컬럼명"),      // [4][5] 컬럼명
                    rs.getString("원본속성명"),      rs.getString("수정속성명"),      // [6][7] 속성명
                    rs.getString("원본컬럼명_카멜"), rs.getString("수정컬럼명_카멜") // [8][9] 카멜케이스
                });
            }
            System.out.println("TOBE 매핑 " + mappings.size() + "건 로드 완료");
        } catch (SQLException e) {
            System.err.println("DB 오류 [" + e.getErrorCode() + "]: " + e.getMessage());
            return;
        }

        // 원본 SQL 파일 읽기
        String content = new String(Files.readAllBytes(sourcePath), StandardCharsets.UTF_8);
        System.out.println("원본 파일 읽기 완료: " + sourcePath.getFileName());

        // 치환 수행 (테이블명 → 컬럼명 → 엔터티명 → 속성명 순서)
        for (String[] m : mappings) {
            content = replace(content, m[0], m[1]); // 원본테이블명 → 수정테이블명
            content = replace(content, m[2], m[3]); // 원본엔터티명 → 수정엔터티명
            content = replace(content, m[4], m[5]); // 원본컬럼명   → 수정컬럼명
            //content = replace(content, m[6], m[7]); // 원본속성명   → 수정속성명
            content = replace(content, m[8], m[9]); // 원본카멜     → 수정카멜
        }
        content = replace(content, "VVAB_CODECO", "TMDM_CODECO"); //공통코드
        content = replace(content, "VVAC_SPORTR", "VWV_APLCT_SCCND_POOL_01"); //지원자 view
        content = replace(content, "FMDM_CODE_NM", "FC_GET_FMDM_CODE_NM");
        content = replace(content, "<if test=\"@org.apache.commons.lang3.StringUtils@isNotEmpty(sDmstcEdcYear)\">", "<if test=\"dmstEduYr != null and dmstEduYr != ''\">"); //국내교육연도
        content = replace(content, "<if test=\"@org.apache.commons.lang3.StringUtils@isNotEmpty(sUnmeStleCd)\">", "<if test=\"unmeShpCd != null and unmeShpCd != ''\">"); //단원형태코드
        content = replace(content, "<if test=\"@org.apache.commons.lang3.StringUtils@isNotEmpty(sRcritGrnu)\">", "<if test=\"rcrtChrtNo != null and rcrtChrtNo != ''\">"); //모집기수
        content = replace(content, "<if", "--<if");
        content = replace(content, "</if>", "--</if>");
        content = replace(content, "&lt;&gt;", "<![CDATA[ <> ]]>");
        content = content.replaceAll("#\\{([^}]+)\\}", "'#{$1}'");  // #{임의문자} → '#{임의문자}'

         //<if test="(majorUnmeShpCd != null and majorUnmeShpCd != '') and (unmeShpCd == null or unmeShpCd == '')">
         //       AND T1.UNME_SHP_CD IN (SELECT CD_VALUE FROM TMDM_CODECO WHERE CD_ID = '42015' AND USE_CD_ONE_VALUE = #{majorUnmeShpCd} )
         // </if>

        // 결과 저장
        Files.write(outputPath, content.getBytes(StandardCharsets.UTF_8));
        System.out.println(SOURCE_SQL+"_tobe.sql 생성 완료: " + outputPath);
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private static String replace(String content, String from, String to) {
        if (from == null || from.trim().isEmpty() || to == null) return content;
        String f = from.trim();
        // 식별자 문자(\w)일 때만 \b 추가 — '<' 같은 기호는 \b 없이 그대로 매칭
        String pre  = isWordChar(f.charAt(0))              ? "\\b" : "";
        String post = isWordChar(f.charAt(f.length() - 1)) ? "\\b" : "";
        return content.replaceAll(pre + Pattern.quote(f) + post,
                                  Matcher.quoteReplacement(to.trim()));
    }
}
