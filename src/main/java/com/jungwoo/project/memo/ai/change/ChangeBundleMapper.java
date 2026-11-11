package com.jungwoo.project.memo.ai.change;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface ChangeBundleMapper {

    void insertBundle(ChangeBundleRows.Bundle bundle);

    void insertItem(ChangeBundleRows.Item item);

    ChangeBundleRows.Bundle findBundle(@Param("bundleId") Long bundleId, @Param("userId") Long userId);

    ChangeBundleRows.Bundle findBundleForUpdate(@Param("bundleId") Long bundleId, @Param("userId") Long userId);

    ChangeBundleRows.Bundle findByApplyKey(@Param("userId") Long userId, @Param("key") String key);

    List<ChangeBundleRows.Item> findItems(@Param("bundleId") Long bundleId, @Param("userId") Long userId);

    /** 묶음을 잠근 뒤 최신 항목(트랜잭션 스냅샷이 아니라 최신 커밋)을 읽는다 — 같은 키 재응답이 옛 상태를 보이지 않게. */
    List<ChangeBundleRows.Item> findItemsForUpdate(@Param("bundleId") Long bundleId, @Param("userId") Long userId);

    List<ChangeBundleRows.Bundle> findByConversation(@Param("conversationId") Long conversationId,
                                                     @Param("userId") Long userId);

    List<ChangeBundleRows.Bundle> findBySourceMessage(@Param("sourceMessageId") Long sourceMessageId,
                                                      @Param("userId") Long userId);

    /** 선택만 바꾸고 리비전을 올린다. 기대 리비전이 맞을 때만(0이면 경합). */
    int updateSelection(@Param("itemId") Long itemId, @Param("bundleId") Long bundleId, @Param("selected") boolean selected);

    int updateItemCheck(@Param("itemId") Long itemId, @Param("bundleId") Long bundleId, @Param("opJson") String opJson,
                        @Param("checkCode") String checkCode, @Param("checkMessage") String checkMessage);

    int bumpRevision(@Param("bundleId") Long bundleId, @Param("userId") Long userId,
                     @Param("expectedRevision") int expectedRevision, @Param("basisFingerprint") String basisFingerprint,
                     @Param("rangeStart") LocalDate rangeStart, @Param("rangeEnd") LocalDate rangeEnd);

    int resolveItem(@Param("itemId") Long itemId, @Param("bundleId") Long bundleId, @Param("status") String status,
                    @Param("resultJson") String resultJson);

    int resolveBundle(@Param("bundleId") Long bundleId, @Param("userId") Long userId, @Param("status") String status,
                      @Param("key") String key, @Param("requestHash") String requestHash,
                      @Param("resultJson") String resultJson);
}
