package com.miuxuer.linkforge.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 访问量排行里的一条短链。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LinkTopVO {

    private Long id;

    private String shortCode;

    private String title;

    private Long visitCount;
}
