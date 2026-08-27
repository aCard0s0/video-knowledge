package com.tradinglabs.vidingest.youtube.repo;

import com.tradinglabs.vidingest.youtube.domain.YoutubeChannelVideo;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface YoutubeChannelVideoRepository extends JpaRepository<YoutubeChannelVideo, UUID> {

    Page<YoutubeChannelVideo> findAllByChannel_Id(UUID channelId, Pageable pageable);

    /**
     * The catalog minus whatever has already been ingested.
     *
     * The console's "not ingested only" filter used to run client-side over one page, so the pager
     * still reported the unfiltered total: ingest thirty and page 0 showed twenty rows under
     * "1–50 of 200", and a mostly-ingested catalog became four pages of near-empty tables. The
     * page has to be filtered before it is counted, which only the query can do.
     *
     * <p>`source`/`sourceVideoId` is the video's identity, the same pair
     * `ingestedYoutubeVideoIds` looks up to mark the rows this one leaves out.
     */
    @Query("""
            select v from YoutubeChannelVideo v
            where v.channel.id = :channelId
              and not exists (
                select 1 from Video vid
                where vid.source = 'youtube' and vid.sourceVideoId = v.youtubeVideoId
              )
            """)
    Page<YoutubeChannelVideo> findNotIngestedByChannelId(@Param("channelId") UUID channelId, Pageable pageable);

    long countByChannel_Id(UUID channelId);

    List<YoutubeChannelVideo> findAllByChannel_IdAndYoutubeVideoIdIn(UUID channelId, Collection<String> youtubeVideoIds);
}

