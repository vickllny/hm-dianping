package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.service.IFollowService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/follow")
public class FollowController {


    @Autowired
    private IFollowService iFollowService;

    @PutMapping(value = "/{followUserId}/{flag}")
    public Result follow(@PathVariable(value = "followUserId")final Long followUserId,
                         @PathVariable(value = "flag")final Boolean flag){
        return iFollowService.follow(followUserId, flag);
    }

    @GetMapping(value = "/or/not/{followUserId}")
    public Result followOrNot(@PathVariable(value = "followUserId")final Long followUserId){
        return iFollowService.followOrNot(followUserId);
    }

    @GetMapping(value = "/common/{userId}")
    public Result common(@PathVariable(value = "userId")final Long userId){
        return iFollowService.common(userId);
    }
}
