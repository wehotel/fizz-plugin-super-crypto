package we.plugin.crypto;

import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.annotation.Resource;

import org.reactivestreams.Publisher;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.NettyDataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONPath;
import com.alibaba.nacos.common.utils.JacksonUtils;
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.collect.Lists;

import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.asymmetric.AsymmetricAlgorithm;
import cn.hutool.crypto.asymmetric.KeyType;
import cn.hutool.crypto.digest.DigestAlgorithm;
import cn.hutool.crypto.symmetric.SymmetricAlgorithm;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import we.plugin.FizzPluginFilter;
import we.plugin.FizzPluginFilterChain;
import we.plugin.crypto.bean.CommonConstant;
import we.plugin.crypto.bean.ParamsEnum;
import we.plugin.crypto.bean.StateCode;
import we.plugin.crypto.bean.StateInfo;
import we.plugin.crypto.service.CryptoService;
import we.proxy.Route;
import we.spring.http.server.reactive.ext.FizzServerHttpRequestDecorator;
import we.spring.http.server.reactive.ext.FizzServerHttpResponseDecorator;
import we.util.NettyDataBufferUtils;
import we.util.WebUtils;

/**
 *  
 * @author  lml.li
 * @date  2021-10-12 15:36:52
 */
@Component(CryptoPluginFilter.CRYPTO_PLUGIN) // ????????????????????? id
@Slf4j
public class CryptoPluginFilter implements FizzPluginFilter {

	public static final String CRYPTO_PLUGIN = "superCryptoPlugin"; // ?????? id

	@Resource
	CryptoService crptoService;

	public CryptoService getCrptoService() {
		return crptoService;
	}

	public void setCrptoService(CryptoService crptoService) {
		this.crptoService = crptoService;
	}

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, Map<String, Object> config) {
		Route route = WebUtils.getRoute(exchange);
		log.warn("------------" + JacksonUtils.toJson(route));
		String secretKey = null;
		String cryptoType = null;
		Integer mode = null;
		String algorithm = null;
		Integer keyType = null;
		String jsonPath = null;
		if (ObjectUtil.isNotEmpty(config)) {
			algorithm = MapUtil.getStr(config, ParamsEnum.ALGORITHM.getName());
			secretKey = MapUtil.getStr(config, ParamsEnum.SECRET_KEY.getName());
			cryptoType = MapUtil.getStr(config, ParamsEnum.CRYPTO_TYPE.getName());
			mode = MapUtil.getInt(config, ParamsEnum.MODE.getName());
			keyType = MapUtil.getInt(config, ParamsEnum.KEY_TYPE.getName());
			jsonPath = MapUtil.getStr(config, ParamsEnum.JSON_PATH.getName());
		}

		// ??????????????????
		StateInfo<String> stateInfo = parameterVerification(cryptoType, algorithm, mode);
		if (ObjectUtil.isNotEmpty(stateInfo) && !StrUtil.equals(stateInfo.getCode(), StateCode.SUCCESS.getCode())) {
			return WebUtils.buildDirectResponse(exchange, HttpStatus.OK, null, stateInfo.getMsg());
		}

		Mono<Void> mono = null;
		// request.headers?????????
		if (StrUtil.equals(cryptoType, CommonConstant.REQUEST_HEADERS_CRYPT)) {
			mono = requestHeadersCrypto(exchange, config, secretKey, mode, algorithm, keyType, jsonPath);
		}
		// request?????????
		if (StrUtil.equals(cryptoType, CommonConstant.REQUEST_CRYPT)) {
			mono = requestCrypto(exchange, config, secretKey, mode, algorithm, keyType, jsonPath);
		}
		// response.headers?????????
		if (StrUtil.equals(cryptoType, CommonConstant.RESPONSE_HEADERS_CRYPT)) {
			mono = responseHeadersCrypto(exchange, config, secretKey, mode, algorithm, keyType, jsonPath);
		}
		// response.body?????????
		if (StrUtil.equals(cryptoType, CommonConstant.RESPONSE_CRYPT)) {
			mono = responseCrypto(exchange, config, secretKey, mode, algorithm, keyType, jsonPath);
		}

		return mono;
	}

	/**   
	 * request.headers???????????????
	 * @param exchange
	 * @param config
	 * @param secretKey ??????
	 * @param mode ?????? 1-?????????2-??????
	 * @param algorithm ??????
	 * @param keyTypeValue ???????????????1-?????????2-?????????3-??????
	 * @return Mono<Void>           
	 */
	private Mono<Void> requestHeadersCrypto(ServerWebExchange exchange, Map<String, Object> config, String secretKey, Integer mode, String algorithm, Integer keyTypeValue, String jsonPath) {
		ServerHttpRequest request = exchange.getRequest();
		FizzServerHttpRequestDecorator requestDecorator = null;
		if (request instanceof FizzServerHttpRequestDecorator) {
			requestDecorator = (FizzServerHttpRequestDecorator) request;
		} else {
			requestDecorator = new FizzServerHttpRequestDecorator(request);
		}

		HttpHeaders headers = requestDecorator.getHeaders();
		KeyType keyType = null;
		if (keyTypeValue != null && keyTypeValue == KeyType.PublicKey.getValue()) {
			keyType = KeyType.PublicKey;
		} else if (keyTypeValue != null && keyTypeValue == KeyType.PrivateKey.getValue()) {
			keyType = KeyType.PrivateKey;
		}
		if (StrUtil.isNotBlank(jsonPath)) {
			// ?????????????????????
			List<String> nameList = Splitter.on(",").splitToList(jsonPath);
			for (String headerName : nameList) {
				String headerValue = headers.getFirst(headerName);

				if (headers.containsKey(headerName) && StrUtil.isNotBlank(jsonPath)) {
					log.info("headers.name={}???headers.originValue={}", headerName, headerValue);
					StateInfo<String> result = dataCrypto(algorithm, secretKey, headerValue, mode, keyType);
					if (ObjectUtil.isNotEmpty(result) && !StrUtil.equals(result.getCode(), StateCode.SUCCESS.getCode())) {
						log.error("???????????????????????????,exception={}", result);
						return WebUtils.responseError(exchange, Integer.valueOf(result.getCode()), result.getMsg());
					}
					String newValue = result.getData();
					headers.set(headerName, newValue);
					log.info("headers.name={}???headers.newValue={}", headerName, newValue);
				}

			}
		}
		return FizzPluginFilterChain.next(exchange);
	}

	/**   
	 * request?????????????????????
	 * @param exchange
	 * @param config
	 * @param secretKey ??????
	 * @param mode ?????? 1-?????????2-??????
	 * @param algorithm ??????
	 * @param keyTypeValue ???????????????1-?????????2-?????????3-??????
	 * @return Mono<Void>       
	 */
	private Mono<Void> requestCrypto(ServerWebExchange exchange, Map<String, Object> config, String secretKey, Integer mode, String algorithm, Integer keyTypeValue, String jsonPath) {
		ServerHttpRequest request = exchange.getRequest();

		Mono<Void> mono = null;
		if (request.getMethod().equals(HttpMethod.GET)) {
			FizzServerHttpRequestDecorator requestDecorator = (FizzServerHttpRequestDecorator) exchange.getRequest();
			KeyType keyType = null;
			if (keyTypeValue != null && keyTypeValue == KeyType.PublicKey.getValue()) {
				keyType = KeyType.PublicKey;
			} else if (keyTypeValue != null && keyTypeValue == KeyType.PrivateKey.getValue()) {
				keyType = KeyType.PrivateKey;
			}
			// ??????GET????????????
			Map<String, String> paramsMap = requestDecorator.getQueryParams().toSingleValueMap();
			log.info("originParamsMap={}", paramsMap == null ? null : paramsMap);

			if (paramsMap != null) {
				for (Map.Entry<String, String> entry : paramsMap.entrySet()) {
					String fieldKey = entry.getKey();
					String fieldValue = entry.getValue();

					if (StrUtil.isBlank(jsonPath) && StrUtil.isNotBlank(fieldValue)) {
						// jsonPath???????????????????????????
						StateInfo<String> result = dataCrypto(algorithm, secretKey, fieldValue, mode, keyType);
						if (ObjectUtil.isNotEmpty(result) && !StrUtil.equals(result.getCode(), StateCode.SUCCESS.getCode())) {
							log.error("???????????????????????????,exception={}", result);
							return WebUtils.responseError(exchange, Integer.valueOf(result.getCode()), result.getMsg());
						}
						String newValue = result.getData();
						paramsMap.put(fieldKey, StrUtil.isNotBlank(newValue) ? newValue : "");

					} else if (StrUtil.isNotBlank(jsonPath)) {

						// ?????????????????????
						List<String> nameList = Splitter.on(",").splitToList(jsonPath);
						for (String name : nameList) {
							if (name.equals(fieldKey) && StrUtil.isNotBlank(fieldValue)) {
								StateInfo<String> result = dataCrypto(algorithm, secretKey, fieldValue, mode, keyType);
								if (ObjectUtil.isNotEmpty(result) && !StrUtil.equals(result.getCode(), StateCode.SUCCESS.getCode())) {
									log.error("???????????????????????????,exception={}", result);
									return WebUtils.responseError(exchange, Integer.valueOf(result.getCode()), result.getMsg());
								}
								String newValue = result.getData();
								paramsMap.put(fieldKey, StrUtil.isNotBlank(newValue) ? newValue : "");
							}
						}
					}

				}
			}

			log.info("newParamsMap={}", paramsMap);

			List<String> uriList = null;
			if (paramsMap != null) {
				uriList = paramsMap.entrySet().stream().map(param -> {
					String data = param.getKey() + "=" + param.getValue();
					return data;
				}).collect(Collectors.toList());
			}
			String uriStr = Joiner.on('&').join(uriList);

			log.info("uri==" + uriStr);
			// {{?????????
			Route route = WebUtils.getRoute(exchange);
			route.query(uriStr);
			// }}

			ServerWebExchange newExchange = exchange.mutate().request(requestDecorator).build();
			mono = FizzPluginFilterChain.next(newExchange);

		} else if (request.getMethod().equals(HttpMethod.POST) && request.getHeaders().getContentType().equals(MediaType.APPLICATION_JSON)) {
			FizzServerHttpRequestDecorator requestDecorator = (FizzServerHttpRequestDecorator) exchange.getRequest();
			mono = requestDecorator.getBody().defaultIfEmpty(NettyDataBufferUtils.EMPTY_DATA_BUFFER).single().flatMap(body -> {
				String originReqBody = body.toString(StandardCharsets.UTF_8); // ???????????????????????????
				log.info("originReqBody={}", originReqBody);
				KeyType keyType = null;
				if (keyTypeValue != null && keyTypeValue == KeyType.PublicKey.getValue()) {
					keyType = KeyType.PublicKey;
				} else if (keyTypeValue != null && keyTypeValue == KeyType.PrivateKey.getValue()) {
					keyType = KeyType.PrivateKey;
				}
				StateInfo<String> result = getCryptoData(originReqBody, secretKey, mode, algorithm, keyType, jsonPath);
				if (ObjectUtil.isNotEmpty(result) && !StrUtil.equals(result.getCode(), StateCode.SUCCESS.getCode())) {
					return WebUtils.responseError(exchange, Integer.valueOf(result.getCode()), result.getMsg());
				}

				String newReqBody = result.getData();
				log.info("newReqBody={}", newReqBody);
				requestDecorator.setBody(newReqBody);
				requestDecorator.getHeaders().remove(HttpHeaders.CONTENT_LENGTH);
				return FizzPluginFilterChain.next(exchange); // ?????????????????????????????????
			});
		}
		return mono;
	}

	/**   
	 * responseHeaders???????????????
	 * @param exchange
	 * @param config
	 * @param secretKey ??????
	 * @param mode ?????? 1-?????????2-??????
	 * @param algorithm ??????
	 * @param keyTypeValue ???????????????1-?????????2-?????????3-??????
	 * @return Mono<Void>             
	 */
	private Mono<Void> responseHeadersCrypto(ServerWebExchange exchange, Map<String, Object> config, String secretKey, Integer mode, String algorithm, Integer keyTypeValue, String jsonPath) {
		ServerHttpResponse response = exchange.getResponse();
		FizzServerHttpResponseDecorator responseDecorator = new FizzServerHttpResponseDecorator(response) {
			@Override
			public Publisher<? extends DataBuffer> writeWith(DataBuffer remoteResponseBody) {

				KeyType keyType = null;
				if (keyTypeValue != null && keyTypeValue == KeyType.PublicKey.getValue()) {
					keyType = KeyType.PublicKey;
				}
				if (keyTypeValue != null && keyTypeValue == KeyType.PrivateKey.getValue()) {
					keyType = KeyType.PrivateKey;
				}
				HttpHeaders headers = response.getHeaders();
				if (StrUtil.isNotBlank(jsonPath)) {
					// ?????????????????????
					List<String> nameList = Splitter.on(",").splitToList(jsonPath);
					for (String headerName : nameList) {
						String headerValue = headers.getFirst(headerName);

						if (headers.containsKey(headerName) && StrUtil.isNotBlank(jsonPath)) {
							log.info("headers.name={}???headers.originValue={}", headerName, headerValue);
							StateInfo<String> result = dataCrypto(algorithm, secretKey, headerValue, mode, keyType);
							if (ObjectUtil.isNotEmpty(result) && !StrUtil.equals(result.getCode(), StateCode.SUCCESS.getCode())) {
								log.error("???????????????????????????,exception={}", result);
								String errorJson = WebUtils.jsonRespBody(1001, "????????????");
								return Mono.just(response.bufferFactory().wrap(errorJson.getBytes()));
							}
							String newValue = result.getData();
							headers.set(headerName, newValue);
							log.info("headers.name={}???headers.newValue={}", headerName, newValue);
						}

					}
				}
				return Mono.just(remoteResponseBody);
			}
		};
		ServerWebExchange build = exchange.mutate().response(responseDecorator).build();
		return FizzPluginFilterChain.next(build); // ?????????????????????????????????
	}

	/**   
	 * Response?????????
	 * @param exchange
	 * @param config
	 * @param secretKey ??????
	 * @param mode ?????? 1-?????????2-??????
	 * @param algorithm ??????
	 * @param keyTypeValue ???????????????1-?????????2-?????????3-??????
	 * @return Mono<Void> 
	 */
	private Mono<Void> responseCrypto(ServerWebExchange exchange, Map<String, Object> config, String secretKey, Integer mode, String algorithm, Integer keyTypeValue, String jsonPath) {
		ServerHttpResponse response = exchange.getResponse();
		FizzServerHttpResponseDecorator fizzServerHttpResponseDecorator = new FizzServerHttpResponseDecorator(response) {
			@Override
			public Publisher<? extends DataBuffer> writeWith(DataBuffer remoteResponseBody) {
				String originRespBody = remoteResponseBody.toString(StandardCharsets.UTF_8);
				log.info("originRespBody={}", originRespBody);

				KeyType keyType = null;
				if (keyTypeValue != null && keyTypeValue == KeyType.PublicKey.getValue()) {
					keyType = KeyType.PublicKey;
				}
				if (keyTypeValue != null && keyTypeValue == KeyType.PrivateKey.getValue()) {
					keyType = KeyType.PrivateKey;
				}
				StateInfo<String> result = getCryptoData(originRespBody, secretKey, mode, algorithm, keyType, jsonPath);
				if (ObjectUtil.isNotEmpty(result) && !StrUtil.equals(result.getCode(), StateCode.SUCCESS.getCode())) {
					log.error("???responseBody??????????????????????????????,exception={}", result);
				}
				String newRespBody = result.getData();
				log.info("newRespBody={}", newRespBody);

				NettyDataBuffer from = NettyDataBufferUtils.from(newRespBody);
				return Mono.just(from);
			}
		};
		ServerWebExchange build = exchange.mutate().response(fizzServerHttpResponseDecorator).build();
		return FizzPluginFilterChain.next(build); // ?????????????????????????????????

	}

	/**   
	 * ??????????????????????????????
	 * @param algorithm ??????
	 * @param keyBase64 ??????
	 * @param originData ??????????????????
	 * @param mode	1-?????????2-??????
	 * @return StateInfo<String>
	 */
	private StateInfo<String> dataCrypto(String algorithm, String keyBase64, String originData, int mode, KeyType keyType) {
		StateInfo<String> stateInfo = new StateInfo<String>(StateCode.SUCCESS.getCode(), StateCode.SUCCESS.getName(), null);

		// ?????????????????????
		Set<String> symmetricAlgorithmSets = Lists.newArrayList(SymmetricAlgorithm.values()).stream().map(SymmetricAlgorithm::getValue).collect(Collectors.toSet());
		if (symmetricAlgorithmSets.contains(algorithm)) {
			stateInfo = crptoService.symmetricCrypto(algorithm, keyBase64, originData, mode);
		}
		// ????????????????????????
		Set<String> asymmetricAlgorithmSets = Lists.newArrayList(AsymmetricAlgorithm.values()).stream().map(AsymmetricAlgorithm::getValue).collect(Collectors.toSet());
		if (asymmetricAlgorithmSets.contains(algorithm)) {
			stateInfo = crptoService.asymmetricCrypto(algorithm, keyBase64, originData, mode, keyType);
		}
		// ????????????
		Set<String> digestAlgorithmSets = Lists.newArrayList(DigestAlgorithm.values()).stream().map(DigestAlgorithm::getValue).collect(Collectors.toSet());
		if (digestAlgorithmSets.contains(algorithm)) {
			stateInfo = crptoService.digester(algorithm, originData);
		}

		return stateInfo;
	}

	/**   
	 * ??????????????????
	 * @param cryptType
	 * @param algorithm
	 * @param mode
	 * @return StateInfo<String>       
	 */
	private StateInfo<String> parameterVerification(String cryptoType, String algorithm, Integer mode) {

		StateInfo<String> stateInfo = new StateInfo<String>(StateCode.SUCCESS.getCode(), StateCode.SUCCESS.getName(), null);
		if (StrUtil.isBlank(cryptoType)) {
			String msg = MessageFormat.format(StateCode.PARAM_MISS.getName(), "cryptoType");
			stateInfo.setCode(StateCode.PARAM_MISS.getCode());
			stateInfo.setMsg(msg);
		}
		if (mode == null) {
			String msg = MessageFormat.format(StateCode.PARAM_MISS.getName(), "mode");
			stateInfo.setCode(StateCode.PARAM_MISS.getCode());
			stateInfo.setMsg(msg);
		}
		if (StrUtil.isBlank(algorithm)) {
			String msg = MessageFormat.format(StateCode.PARAM_MISS.getName(), "algorithm");
			stateInfo.setCode(StateCode.PARAM_MISS.getCode());
			stateInfo.setMsg(msg);
		}

		return stateInfo;
	}

	/**   
	 * 
	 * @param originData
	 * @param secretKey
	 * @param cryptoType
	 * @param mode
	 * @param algorithm
	 * @param keyTypeValue
	 * @param jsonPath
	 * @return          
	 */
	private StateInfo<String> getCryptoData(String originData, String secretKey, Integer mode, String algorithm, KeyType keyType, String jsonPath) {
		String newData = null;

		// ??????jsonPath???json?????????????????????????????????
		if (StrUtil.isNotBlank(originData) && StrUtil.isNotBlank(jsonPath)) {
			Object json = JSON.parse(originData);

			// ??????jsonPath?????????
			String pathValue = JSONPath.eval(json, jsonPath).toString();
			StateInfo<String> result = dataCrypto(algorithm, secretKey, pathValue, mode, keyType);
			if (ObjectUtil.isNotEmpty(result) && !StrUtil.equals(result.getCode(), StateCode.SUCCESS.getCode())) {
				return result;
			}
			// ?????????
			boolean isSuc = JSONPath.set(json, jsonPath, result.getData());
			if (isSuc) {
				newData = JSON.toJSONString(json);
			} else {
				return StateInfo.error("????????????jsonPath?????????");
			}

		} else if (StrUtil.isNotBlank(originData) && StrUtil.isBlank(jsonPath)) {
			// ?????????json??????????????????
			StateInfo<String> result = dataCrypto(algorithm, secretKey, originData, mode, keyType);
			if (ObjectUtil.isNotEmpty(result) && !StrUtil.equals(result.getCode(), StateCode.SUCCESS.getCode())) {
				return result;
			}
			if (ObjectUtil.isNotEmpty(result) && StrUtil.equals(result.getCode(), StateCode.SUCCESS.getCode())) {
				newData = result.getData();
			}
		}

		return StateInfo.success(newData);
	}
}
